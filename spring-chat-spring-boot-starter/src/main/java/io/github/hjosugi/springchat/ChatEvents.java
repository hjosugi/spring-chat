package io.github.hjosugi.springchat;

import java.time.Duration;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Flux;

import org.springframework.util.StringUtils;

/**
 * Turns a {@link ChatService} reply into the {@link ChatEvent}s both transports send.
 */
public class ChatEvents {

	/**
	 * How long a reply may send nothing before a {@code ping} goes out. Proxies drop
	 * connections that stay silent, and Spring MVC notices that the browser has gone
	 * only when it writes.
	 */
	static final Duration KEEP_ALIVE = Duration.ofSeconds(5);

	private static final int MAX_CONVERSATION_ID_LENGTH = 100;

	private static final Log logger = LogFactory.getLog(ChatEvents.class);

	private final ChatService chatService;

	private final int maxMessageLength;

	public ChatEvents(ChatService chatService, int maxMessageLength) {
		this.chatService = chatService;
		this.maxMessageLength = maxMessageLength;
	}

	/**
	 * The events of one reply. The stream never fails: a failure is its last event.
	 */
	public Flux<ChatEvent> stream(ChatRequest request) {
		String rejection = reject(request);
		if (rejection != null) {
			return Flux.just(ChatEvent.start(), ChatEvent.error(rejection));
		}
		Flux<ChatEvent> reply = Flux.defer(() -> this.chatService.stream(request))
			.filter((delta) -> !delta.isEmpty())
			.map(ChatEvent::text)
			.startWith(ChatEvent.start())
			.concatWithValues(ChatEvent.done())
			.onErrorResume((ex) -> {
				// The cause may name the model provider or a key, so the browser gets none of it.
				logger.error("The chat reply failed", ex);
				return Flux.just(ChatEvent.error("The reply could not be completed."));
			});
		Flux<ChatEvent> pings = Flux.interval(KEEP_ALIVE).onBackpressureDrop().map((tick) -> ChatEvent.ping());
		return reply.mergeWith(pings).takeUntil(ChatEvent::endsReply);
	}

	private String reject(ChatRequest request) {
		if (!StringUtils.hasText(request.text()) || !StringUtils.hasText(request.conversationId())) {
			return "A message needs a conversationId and a text.";
		}
		if (request.conversationId().length() > MAX_CONVERSATION_ID_LENGTH) {
			return "The conversationId is longer than %d characters.".formatted(MAX_CONVERSATION_ID_LENGTH);
		}
		if (request.text().length() > this.maxMessageLength) {
			return "The message is longer than %d characters.".formatted(this.maxMessageLength);
		}
		return null;
	}

}

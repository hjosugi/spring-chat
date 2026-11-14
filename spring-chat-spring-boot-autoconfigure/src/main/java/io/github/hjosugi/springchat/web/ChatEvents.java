package io.github.hjosugi.springchat.web;

import java.security.Principal;
import java.time.Duration;

import io.github.hjosugi.springchat.ChatRequest;
import io.github.hjosugi.springchat.ChatService;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

import org.springframework.util.StringUtils;

/**
 * Checks what the browser sent and turns the {@link ChatService}'s reply into the
 * {@link ChatEvent}s both transports send.
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
	 * The events of the reply to one message. The stream never fails: a failure is its
	 * last event.
	 * @param message what the browser sent
	 * @param principal who sent it, if they signed in
	 */
	public Flux<ChatEvent> stream(BrowserMessage message, @Nullable Principal principal) {
		String conversationId = message.conversationId();
		String text = message.text();
		if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(text)) {
			return rejected("A message needs a conversationId and a text.");
		}
		if (conversationId.length() > MAX_CONVERSATION_ID_LENGTH) {
			return rejected("The conversationId is longer than %d characters.".formatted(MAX_CONVERSATION_ID_LENGTH));
		}
		if (text.length() > this.maxMessageLength) {
			return rejected("The message is longer than %d characters.".formatted(this.maxMessageLength));
		}
		return stream(new ChatRequest(conversationId, text, principal));
	}

	private Flux<ChatEvent> stream(ChatRequest request) {
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

	private static Flux<ChatEvent> rejected(String reason) {
		return Flux.just(ChatEvent.start(), ChatEvent.error(reason));
	}

}

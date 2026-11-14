package io.github.hjosugi.springchat;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Flux;

import org.springframework.util.StringUtils;

/**
 * Turns a {@link ChatService} reply into the {@link ChatEvent}s both transports send.
 */
public class ChatEvents {

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
		if (request == null || !StringUtils.hasText(request.text()) || !StringUtils.hasText(request.conversationId())) {
			return Flux.just(ChatEvent.error("A message needs a conversationId and a text."));
		}
		if (request.text().length() > this.maxMessageLength) {
			return Flux.just(ChatEvent.error("The message is longer than %d characters.".formatted(this.maxMessageLength)));
		}
		return Flux.defer(() -> this.chatService.stream(request))
			.filter((delta) -> !delta.isEmpty())
			.map(ChatEvent::text)
			.startWith(ChatEvent.start())
			.concatWithValues(ChatEvent.done())
			.onErrorResume((ex) -> {
				// The cause may name the model provider or a key, so the browser gets none of it.
				logger.error("The chat reply failed", ex);
				return Flux.just(ChatEvent.error("The reply could not be completed."));
			});
	}

}

package io.github.hjosugi.springchat.web;

import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.hjosugi.springchat.ChatReply;
import io.github.hjosugi.springchat.ChatRequest;
import io.github.hjosugi.springchat.ChatService;
import io.github.hjosugi.springchat.ToolDecision;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

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

	private static final int MAX_DECISIONS = 32;

	private static final int MAX_TOOL_CALL_ID_LENGTH = 200;

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
		List<ToolDecision> decisions = decisions(message);
		if (decisions == null) {
			return rejected("The decisions are not valid.");
		}
		if (!StringUtils.hasText(conversationId) || StringUtils.hasText(text) == !decisions.isEmpty()) {
			return rejected("A message needs a conversationId and a text, or decisions.");
		}
		if (conversationId.length() > MAX_CONVERSATION_ID_LENGTH) {
			return rejected("The conversationId is longer than %d characters.".formatted(MAX_CONVERSATION_ID_LENGTH));
		}
		if (text != null && text.length() > this.maxMessageLength) {
			return rejected("The message is longer than %d characters.".formatted(this.maxMessageLength));
		}
		return stream(new ChatRequest(conversationId, decisions.isEmpty() ? text : "", principal, decisions));
	}

	/**
	 * The decisions of a message: none, or {@code null} if one is not valid.
	 */
	private static @Nullable List<ToolDecision> decisions(BrowserMessage message) {
		if (message.decisions() == null || message.decisions().isEmpty()) {
			return List.of();
		}
		if (message.decisions().size() > MAX_DECISIONS) {
			return null;
		}
		List<ToolDecision> decisions = new ArrayList<>();
		for (BrowserMessage.Decision decision : message.decisions()) {
			if (decision == null || !StringUtils.hasText(decision.id())
					|| decision.id().length() > MAX_TOOL_CALL_ID_LENGTH) {
				return null;
			}
			decisions.add(new ToolDecision(decision.id(), decision.allow()));
		}
		return decisions;
	}

	private Flux<ChatEvent> stream(ChatRequest request) {
		Flux<ChatEvent> reply = Flux.defer(() -> this.chatService.reply(request))
			// Not on the thread of the web server: a model may read its whole stream on the
			// thread that subscribes, as Spring AI's Gemini model does, and that thread
			// could then neither write the reply nor take a stop.
			.subscribeOn(Schedulers.boundedElastic())
			.map(ChatEvents::event)
			.filter((event) -> !ChatEvent.TEXT.equals(event.type()) || !event.delta().isEmpty())
			.startWith(ChatEvent.start())
			// A reply that the service ends itself has ended: this one is for the others.
			.concatWithValues(ChatEvent.done())
			.onErrorResume((ex) -> {
				// The cause may name the model provider or a key, so the browser gets none of it.
				logger.error("The chat reply failed", ex);
				return Flux.just(ChatEvent.error("The reply could not be completed."));
			});
		Flux<ChatEvent> pings = Flux.interval(KEEP_ALIVE).onBackpressureDrop().map((tick) -> ChatEvent.ping());
		return reply.mergeWith(pings).takeUntil(ChatEvent::endsReply);
	}

	/**
	 * Not a {@code switch} over the sealed {@link ChatReply}: patterns in a switch need
	 * Java 21, and the chat runs on 17.
	 */
	private static ChatEvent event(ChatReply reply) {
		if (reply instanceof ChatReply.Text text) {
			return ChatEvent.text(text.delta());
		}
		if (reply instanceof ChatReply.Confirmation confirmation) {
			return ChatEvent.confirm(confirmation.calls()
				.stream()
				.map((call) -> new ChatEvent.Call(call.id(), call.name(), call.arguments()))
				.toList());
		}
		if (reply instanceof ChatReply.Ended ended) {
			return ChatEvent.done(ended.reason().name().toLowerCase(Locale.ROOT));
		}
		throw new IllegalStateException("Not a reply of the chat: " + reply);
	}

	private static Flux<ChatEvent> rejected(String reason) {
		return Flux.just(ChatEvent.start(), ChatEvent.error(reason));
	}

}

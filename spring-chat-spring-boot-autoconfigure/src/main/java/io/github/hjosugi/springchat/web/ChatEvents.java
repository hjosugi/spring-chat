package io.github.hjosugi.springchat.web;

import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import io.github.hjosugi.springchat.AttachmentProperties;
import io.github.hjosugi.springchat.ChatReply;
import io.github.hjosugi.springchat.ChatRequest;
import io.github.hjosugi.springchat.ChatService;
import io.github.hjosugi.springchat.ToolDecision;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
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

	private final @Nullable AttachmentProperties attachments;

	public ChatEvents(ChatService chatService, int maxMessageLength) {
		this(chatService, maxMessageLength, null);
	}

	public ChatEvents(ChatService chatService, int maxMessageLength, @Nullable AttachmentProperties attachments) {
		this.chatService = chatService;
		this.maxMessageLength = maxMessageLength;
		this.attachments = attachments;
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
		boolean hasFiles = message.attachments() != null && !message.attachments().isEmpty();
		boolean userMessage = StringUtils.hasText(text) || hasFiles;
		boolean hasDecisions = !decisions.isEmpty();
		if (!StringUtils.hasText(conversationId) || userMessage == hasDecisions) {
			return rejected("A message needs a conversationId and either text or attachments, or tool decisions.");
		}
		if (conversationId.length() > MAX_CONVERSATION_ID_LENGTH) {
			return rejected("The conversationId is longer than %d characters.".formatted(MAX_CONVERSATION_ID_LENGTH));
		}
		if (text != null && text.length() > this.maxMessageLength) {
			return rejected("The message is longer than %d characters.".formatted(this.maxMessageLength));
		}
		List<Media> media = List.of();
		if (hasFiles) {
			if (this.attachments == null || !this.attachments.enabled()) {
				return rejected("Attachments are disabled.");
			}
			if (message.attachments().size() > this.attachments.maxCount()) {
				return rejected("There are too many attachments.");
			}
			try {
				media = message.attachments().stream().map((file) -> media(file, this.attachments)).toList();
			}
			catch (IllegalArgumentException ex) {
				return rejected("The attachment has an invalid name, media type, size or base64 data.");
			}
		}
		return stream(new ChatRequest(conversationId, (decisions.isEmpty() && text != null) ? text : "",
				principal, decisions, media));
	}

	private static Media media(BrowserMessage.@Nullable Attachment file, AttachmentProperties limits) {
		if (file == null || !StringUtils.hasText(file.name()) || file.name().length() > 255
				|| !StringUtils.hasText(file.mediaType()) || file.mediaType().length() > 255 || file.data() == null) {
			throw new IllegalArgumentException();
		}
		MimeType type = MimeTypeUtils.parseMimeType(file.mediaType());
		long limit = limits.maxSize().toBytes();
		if (!type.isConcrete() || limits.allowedTypes().stream().noneMatch((allowed) -> allowed.includes(type))
				|| file.data().length() > 4 * ((limit + 2) / 3)) {
			throw new IllegalArgumentException();
		}
		byte[] bytes = Base64.getDecoder().decode(file.data());
		if (bytes.length == 0 || bytes.length > limit) {
			throw new IllegalArgumentException();
		}
		return Media.builder().name(file.name()).mimeType(type).data(bytes).build();
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
		return Flux.defer(() -> {
			AtomicReference<ChatReply.Usage> usage = new AtomicReference<>();
			Flux<ChatEvent> reply = Flux.defer(() -> this.chatService.reply(request))
				// Some models block the subscribing thread while they read their stream.
				.subscribeOn(Schedulers.boundedElastic())
				.<ChatEvent>handle((part, sink) -> {
					if (part instanceof ChatReply.Usage tokens) {
						usage.set(tokens);
					}
					else if (!(part instanceof ChatReply.Text text && text.delta().isEmpty())) {
						sink.next(event(part, usage.get()));
					}
				})
				.startWith(ChatEvent.start())
				.concatWith(Mono.fromSupplier(() -> ChatEvent.done(null, usage.get())))
				.onErrorResume((ex) -> {
					// The cause may name the model provider or a key.
					logger.error("The chat reply failed", ex);
					return Flux.just(ChatEvent.error("The reply could not be completed."));
				});
			Flux<ChatEvent> pings = Flux.interval(KEEP_ALIVE).onBackpressureDrop().map((tick) -> ChatEvent.ping());
			return reply.mergeWith(pings).takeUntil(ChatEvent::endsReply);
		});
	}

	/**
	 * Not a {@code switch} over the sealed {@link ChatReply}: patterns in a switch need
	 * Java 21, and the chat runs on 17.
	 */
	private static ChatEvent event(ChatReply reply, ChatReply.@Nullable Usage usage) {
		if (reply instanceof ChatReply.Text text) {
			return ChatEvent.text(text.delta());
		}
		if (reply instanceof ChatReply.Reasoning reasoning) {
			return ChatEvent.reasoning(reasoning.delta());
		}
		if (reply instanceof ChatReply.ToolCall tool) {
			return ChatEvent.tool(tool.name());
		}
		if (reply instanceof ChatReply.Confirmation confirmation) {
			return ChatEvent.confirm(confirmation.calls()
				.stream()
				.map((call) -> new ChatEvent.Call(call.id(), call.name(), call.arguments()))
				.toList());
		}
		if (reply instanceof ChatReply.Ended ended) {
			return ChatEvent.done(ended.reason().name().toLowerCase(Locale.ROOT), usage);
		}
		throw new IllegalStateException("Not a reply of the chat: " + reply);
	}

	private static Flux<ChatEvent> rejected(String reason) {
		return Flux.just(ChatEvent.start(), ChatEvent.error(reason));
	}

}

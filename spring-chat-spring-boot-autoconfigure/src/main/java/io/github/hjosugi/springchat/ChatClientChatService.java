package io.github.hjosugi.springchat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.DefaultChatClient.DefaultChatClientRequestSpec;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.metadata.FinishReasonCategory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.support.UsageCalculator;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.util.StringUtils;

/**
 * A {@link ChatService} that asks a {@link ChatClient}, the default one. Each
 * conversation is a session of Spring AI, if the client has the session advisors.
 * <p>
 * A tool that needs the user's confirmation pauses the reply, which then ends with a
 * {@link ChatReply.Confirmation}. The conversation goes on with a request that brings
 * the user's decisions: the paused reply is read from the session, so that a browser
 * cannot make up a tool call, the allowed calls run, and the model answers their
 * results.
 */
public class ChatClientChatService implements ChatService {

	/**
	 * The browser chooses its conversation id. The prefix keeps it from naming a
	 * session that the application keeps in the same {@link SessionService}.
	 */
	static final String SESSION_PREFIX = "spring-chat:";

	private final ChatClient chatClient;

	private final List<ChatPromptCustomizer> customizers;

	private final @Nullable SessionService sessions;

	private final ToolCallingManager toolCallingManager;

	/**
	 * The reply that is being written for a session, as the sink that ends it. A session
	 * of Spring AI does not know whether a turn still runs, and two turns that run at once
	 * mix their messages up, so a conversation has one reply at a time.
	 */
	private final Map<String, Sinks.Empty<Void>> running = new ConcurrentHashMap<>();

	/**
	 * A service that adds nothing to the prompts, and cannot continue a paused reply.
	 * @param chatClient asks the model; its defaults hold the system prompt and memory
	 */
	public ChatClientChatService(ChatClient chatClient) {
		this(chatClient, List.of());
	}

	/**
	 * A service that lets customizers add to each prompt, and cannot continue a paused
	 * reply.
	 * @param chatClient asks the model; its defaults hold the system prompt and memory
	 * @param customizers add to each prompt, in this order
	 */
	public ChatClientChatService(ChatClient chatClient, List<ChatPromptCustomizer> customizers) {
		this(chatClient, customizers, null, ToolCallingManager.builder().build());
	}

	/**
	 * A service that can continue a paused reply.
	 * @param chatClient asks the model; its defaults hold the system prompt and the
	 * session advisors
	 * @param customizers add to each prompt, in this order
	 * @param sessions holds the conversations: the service reads a paused reply from
	 * it. The client's session advisors must use the same one
	 * @param toolCallingManager runs the tool calls that the user allowed
	 */
	public ChatClientChatService(ChatClient chatClient, List<ChatPromptCustomizer> customizers,
			@Nullable SessionService sessions, ToolCallingManager toolCallingManager) {
		this.chatClient = chatClient;
		this.customizers = List.copyOf(customizers);
		this.sessions = sessions;
		this.toolCallingManager = toolCallingManager;
	}

	@Override
	public Flux<String> stream(ChatRequest request) {
		return reply(request).ofType(ChatReply.Text.class).map(ChatReply.Text::delta);
	}

	/**
	 * The reply to a message, or the rest of a paused reply. A new message ends the reply
	 * that is still being written for its conversation, as a stop would: over SSE the
	 * server learns that the browser left a reply only when it next writes to it. Decisions
	 * that arrive while a reply is being written are refused, since its tools may be
	 * running.
	 */
	@Override
	public Flux<ChatReply> reply(ChatRequest request) {
		String sessionId = sessionId(request);
		boolean decides = !request.decisions().isEmpty();
		return Flux.defer(() -> {
			Sinks.Empty<Void> stop = Sinks.empty();
			if (decides) {
				if (this.running.putIfAbsent(sessionId, stop) != null) {
					return Flux.error(new IllegalStateException("A reply of this conversation is being written."));
				}
			}
			else {
				Sinks.Empty<Void> earlier = this.running.put(sessionId, stop);
				if (earlier != null) {
					earlier.tryEmitEmpty();
				}
			}
			// The conversation is free when the end of its reply is signalled, and not a moment
			// after: doFinally runs once the signal has been passed on, so whoever answers the
			// end at once would still be refused. A cancel has no end, and doFinally frees it.
			return Flux.defer(() -> decides ? resume(request, sessionId) : turn(request))
				.takeUntilOther(stop.asMono())
				.doOnTerminate(() -> this.running.remove(sessionId, stop))
				.doFinally((signal) -> this.running.remove(sessionId, stop));
		});
	}

	private Flux<ChatReply> turn(ChatRequest request) {
		ChatClient.ChatClientRequestSpec prompt = prompt(request);
		if (request.attachments().isEmpty() || StringUtils.hasText(request.text())) {
			prompt.user(request.text());
			if (!request.attachments().isEmpty()) {
				prompt.user((user) -> user.media(request.attachments().toArray(Media[]::new)));
			}
		}
		else {
			// ChatClient's user spec requires text. A file-only turn is still a user message.
			prompt.messages(UserMessage.builder().text(request.text()).media(request.attachments()).build());
		}
		return replies(prompt);
	}

	/**
	 * The prompt of a request, with the session of its conversation and what the
	 * customizers add.
	 */
	private ChatClient.ChatClientRequestSpec prompt(ChatRequest request) {
		ChatClient.ChatClientRequestSpec prompt = this.chatClient.prompt()
			.advisors(SessionAdvisors.session(sessionId(request)))
			.advisors((advisors) -> advisors.param(SessionMemoryAdvisor.SESSION_METADATA_CONTEXT_KEY,
					sessionMetadata(request)));
		this.customizers.forEach((customizer) -> customizer.customize(prompt, request));
		return prompt;
	}

	/**
	 * What a stream of the model holds, as the pieces of a reply. Read as a
	 * {@code ChatResponse} and not as text, because text alone does not tell a reply that
	 * paused, or that was cut, from one that is complete.
	 */
	private static Flux<ChatReply> replies(ChatClient.ChatClientRequestSpec prompt) {
		prompt.advisors(new ToolProgressAdvisor());
		ReplyChunks reply = new ReplyChunks();
		return prompt.stream().chatResponse().concatMapIterable(reply::read).concatWith(Flux.defer(reply::finish));
	}

	private static final class ReplyChunks {

		private ChatReply.@Nullable Usage usage;

		private @Nullable ChatReply end;

		List<ChatReply> read(ChatResponse chunk) {
			if (chunk.getMetadata().get(ToolProgressAdvisor.TOOL_PROGRESS) instanceof ChatReply.ToolCall progress) {
				return List.of(progress);
			}
			// A provider can put cumulative usage on a trailing chunk with no generation.
			Usage tokens = chunk.getMetadata().getUsage();
			if (!UsageCalculator.isEmpty(tokens)) {
				this.usage = new ChatReply.Usage(tokens.getPromptTokens(), tokens.getCompletionTokens(), tokens.getTotalTokens());
			}
			Generation generation = chunk.getResult();
			if (generation == null) {
				return List.of();
			}
			AssistantMessage message = generation.getOutput();
			List<ChatReply> replies = new ArrayList<>();
			for (MessagePart part : message.getParts()) {
				if (part instanceof TextPart text && !text.text().isEmpty()) {
					replies.add(new ChatReply.Text(text.text()));
				}
				else if (part instanceof ReasoningPart reasoning) {
					String delta = StringUtils.hasLength(reasoning.summary()) ? reasoning.summary() : reasoning.text();
					if (StringUtils.hasLength(delta)) {
						replies.add(new ChatReply.Reasoning(delta));
					}
				}
			}
			if (ToolCallDecisions.isAwaitingDecision(chunk)) {
				this.end = confirmation(message);
			}
			else {
				FinishReasonCategory category = generation.getMetadata().getFinishReasonCategory();
				if (category == FinishReasonCategory.TRUNCATED) {
					this.end = new ChatReply.Ended(ChatReply.Reason.TRUNCATED);
				}
				else if (category == FinishReasonCategory.FILTERED) {
					this.end = new ChatReply.Ended(ChatReply.Reason.FILTERED);
				}
			}
			return replies;
		}

		Flux<ChatReply> finish() {
			return Flux.concat(Mono.justOrEmpty(this.usage), Mono.justOrEmpty(this.end));
		}

		private static ChatReply.Confirmation confirmation(AssistantMessage message) {
			List<String> keys = ToolCallDecisions.toolCallKeys(message);
			List<AssistantMessage.ToolCall> calls = message.getToolCalls();
			List<ChatReply.PendingToolCall> pending = new ArrayList<>();
			for (int i = 0; i < calls.size(); i++) {
				pending.add(new ChatReply.PendingToolCall(keys.get(i), calls.get(i).name(), calls.get(i).arguments()));
			}
			return new ChatReply.Confirmation(pending);
		}
	}

	private Flux<ChatReply> resume(ChatRequest request, String sessionId) {
		AssistantMessage paused = pausedReply(sessionId);
		ChatClient.ChatClientRequestSpec prompt = prompt(request);
		ToolCallingChatOptions options = toolOptions(prompt);
		ToolCallDecisions decisions = decisions(paused, request.decisions());
		// A tool may block, so it must not run on a thread of the web server.
		List<ChatReply.ToolCall> runningTools = new ArrayList<>();
		for (int i = 0; i < decisions.decisions().size(); i++) {
			if (decisions.decisions().get(i).allowed()) {
				runningTools.add(new ChatReply.ToolCall(paused.getToolCalls().get(i).name()));
			}
		}
		Flux<ChatReply> answer = Mono.fromCallable(() -> this.toolCallingManager.executeToolCalls(new Prompt(List.of(), options),
				new ChatResponse(List.of(new Generation(paused))), decisions))
			.subscribeOn(Schedulers.boundedElastic())
			.flatMapMany((result) -> answer(prompt, result));
		return Flux.concat(Flux.fromIterable(runningTools), answer);
	}

	private Flux<ChatReply> answer(ChatClient.ChatClientRequestSpec prompt, ToolExecutionResult result) {
		if (result.returnDirect()) {
			// The tools' results are the answer: the model does not see them.
			return Flux.fromIterable(ToolExecutionResult.buildGenerations(result))
				.map((generation) -> generation.getOutput().getText())
				.filter((text) -> text != null && !text.isEmpty())
				.map(ChatReply.Text::new);
		}
		List<Message> history = result.conversationHistory();
		// Only the results: the session holds the user's message and the paused reply.
		prompt.messages(history.get(history.size() - 1));
		return replies(prompt);
	}

	/**
	 * The reply that waits for a decision. It comes from the session, which the user
	 * cannot change, and never from the browser: whoever could edit it could run any tool
	 * with any arguments.
	 */
	private AssistantMessage pausedReply(String sessionId) {
		if (this.sessions == null || this.sessions.findById(sessionId) == null) {
			throw new IllegalStateException("There is no conversation that waits for a decision.");
		}
		List<Message> messages = this.sessions.getActiveMessages(sessionId);
		if (!messages.isEmpty() && messages.get(messages.size() - 1) instanceof AssistantMessage reply
				&& reply.hasToolCalls()) {
			return reply;
		}
		// Answered already, or the user wrote another message: the paused calls were closed.
		throw new IllegalStateException("Nothing in the conversation waits for a decision.");
	}

	/**
	 * The tools and the tool context of the request, which the manager needs to run the
	 * calls: it runs only tools that the prompt carries, and a tool that reads the tool
	 * context finds none otherwise.
	 */
	private static ToolCallingChatOptions toolOptions(ChatClient.ChatClientRequestSpec prompt) {
		if (!(prompt instanceof DefaultChatClientRequestSpec spec)) {
			throw new IllegalStateException(
					"The tools of a request can be read from the default ChatClient only: " + prompt.getClass());
		}
		List<ToolCallback> callbacks = new ArrayList<>(spec.getToolCallbacks());
		for (ToolCallbackProvider provider : spec.getToolCallbackProviders()) {
			callbacks.addAll(List.of(provider.getToolCallbacks()));
		}
		return ToolCallingChatOptions.builder().toolCallbacks(callbacks).toolContext(spec.getToolContext()).build();
	}

	/**
	 * A decision for every call of the reply, as the manager requires. A call that the
	 * user did not answer is denied.
	 */
	private static ToolCallDecisions decisions(AssistantMessage paused, List<ToolDecision> answers) {
		List<String> keys = ToolCallDecisions.toolCallKeys(paused);
		Map<String, Boolean> allowed = new HashMap<>();
		for (ToolDecision answer : answers) {
			if (!keys.contains(answer.id())) {
				throw new IllegalArgumentException("A decision names a tool call that the reply does not have.");
			}
			allowed.put(answer.id(), answer.allow());
		}
		return new ToolCallDecisions(keys.stream()
			.map((key) -> allowed.getOrDefault(key, false) ? ToolCallDecision.allow(key) : ToolCallDecision.deny(key))
			.toList());
	}

	/**
	 * The session of a conversation. The browser makes up the conversation id, so it
	 * says nothing about who may continue the conversation: two people who send the same
	 * id must not share a session. The signed-in user is part of the id, as a hash that
	 * no other user's name or conversation id can imitate, and does not show in logs or
	 * in the tables of a store.
	 */
	static String sessionId(ChatRequest request) {
		String owner = (request.principal() != null) ? "user-" + hash(request.principal().getName()) : "anonymous";
		return SESSION_PREFIX + owner + ":" + request.conversationId();
	}

	/**
	 * What a session that this message starts is labeled with: who it belongs to, so that
	 * a store can list or delete the sessions of one user.
	 */
	private static Map<String, Object> sessionMetadata(ChatRequest request) {
		return (request.principal() != null)
				? Map.of(SessionAttributes.PRINCIPAL_NAME, request.principal().getName()) : Map.of();
	}

	private static String hash(String name) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 16);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}

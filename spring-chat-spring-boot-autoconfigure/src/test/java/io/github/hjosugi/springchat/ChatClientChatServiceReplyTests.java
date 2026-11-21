package io.github.hjosugi.springchat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * What a reply of the default service holds besides its text. A tool that needs the
 * user's confirmation pauses the reply, and the user's decisions continue it. A reply
 * that the model did not finish says why. A conversation has one reply at a time.
 * <p>
 * The model asks for a tool when it is told to cancel or to find an order, and answers
 * with what the tool returned, which Spring AI hands it as JSON: a string with its
 * quotes.
 */
class ChatClientChatServiceReplyTests {

	private final SessionService sessions = DefaultSessionService.builder()
		.sessionRepository(InMemorySessionRepository.builder().build())
		.build();

	private final OrderTools tools = new OrderTools();

	private final CancellingModel model = new CancellingModel();

	private final ChatClientChatService service = service(
			(prompt, request) -> prompt.tools(this.tools).toolContext(Map.of("user", request.principal().getName())));

	private ChatClientChatService service(ChatPromptCustomizer customizer) {
		ChatClient client = ChatClient.builder(this.model)
			.defaultAdvisors(SessionAdvisors.builder(this.sessions).build())
			.build();
		return new ChatClientChatService(client, List.of(customizer), this.sessions,
				ToolCallingManager.builder().build());
	}

	private static ChatRequest says(String text) {
		return new ChatRequest("c1", text, () -> "alice");
	}

	private static ChatRequest decides(String user, ToolDecision... decisions) {
		return new ChatRequest("c1", "", () -> user, List.of(decisions));
	}

	@Test
	void aToolThatNeedsConfirmationPausesTheReplyAndRunsNothing() {
		List<ChatReply> reply = this.service.reply(says("Cancel A-2")).collectList().block();

		assertThat(reply).hasSize(2);
		assertThat(reply.get(0)).isEqualTo(new ChatReply.Text("Let me cancel that. "));
		ChatReply.Confirmation confirmation = (ChatReply.Confirmation) reply.get(1);
		assertThat(confirmation.calls()).singleElement().satisfies((call) -> {
			assertThat(call.name()).isEqualTo("cancelOrder");
			assertThat(call.arguments()).contains("A-2");
		});
		assertThat(this.tools.cancelled).isEmpty();
	}

	@Test
	void theTextOfAPausedReplyIsStillTheTextOfTheService() {
		assertThat(this.service.stream(says("Cancel A-2")).collectList().block()).containsExactly("Let me cancel that. ");
	}

	@Test
	void allowingTheCallRunsItForTheUserWhoAskedAndTheModelAnswersItsResult() {
		ChatReply.Confirmation confirmation = pause("Cancel A-2");
		String call = confirmation.calls().get(0).id();

		List<ChatReply> answer = this.service.reply(decides("alice", new ToolDecision(call, true)))
			.collectList()
			.block();

		assertThat(this.tools.cancelled).containsExactly("alice cancelled A-2");
		assertThat(answer).containsExactly(new ChatReply.Text("Done: "), new ChatReply.Text("\"alice cancelled A-2\""));
		// The session holds the whole turn: the question, the paused reply, the result and the answer.
		assertThat(this.sessions.getActiveMessages(ChatClientChatService.sessionId(says(""))))
			.extracting((message) -> message.getMessageType().name())
			.containsExactly("USER", "ASSISTANT", "TOOL", "ASSISTANT");
	}

	@Test
	void denyingTheCallRunsNothingAndTheModelHearsIt() {
		ChatReply.Confirmation confirmation = pause("Cancel A-2");

		List<ChatReply> answer = this.service
			.reply(decides("alice", new ToolDecision(confirmation.calls().get(0).id(), false)))
			.collectList()
			.block();

		assertThat(this.tools.cancelled).isEmpty();
		assertThat(answer).contains(new ChatReply.Text("Done: "));
		Message last = this.model.prompts.get(this.model.prompts.size() - 1).getInstructions().getLast();
		assertThat(last).isInstanceOf(ToolResponseMessage.class);
		assertThat(((ToolResponseMessage) last).getResponses().get(0).responseData()).contains("did not allow");
	}

	@Test
	void aCallWithoutADecisionIsDenied() {
		ChatReply.Confirmation confirmation = pause("Cancel A-2");
		ToolDecision other = new ToolDecision("not-" + confirmation.calls().get(0).id(), true);

		assertThatThrownBy(() -> this.service.reply(decides("alice", other)).blockLast())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("does not have");
		assertThat(this.tools.cancelled).isEmpty();
	}

	@Test
	void theBrowserCannotMakeUpATool() {
		// Nothing was paused, so a decision on a call that never was asked for runs nothing.
		assertThatThrownBy(() -> this.service.reply(decides("alice", new ToolDecision("call_1", true))).blockLast())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("no conversation that waits");
		assertThat(this.tools.cancelled).isEmpty();
	}

	@Test
	void anotherUserCannotAllowTheCallsOfAPausedReply() {
		ChatReply.Confirmation confirmation = pause("Cancel A-2");

		assertThatThrownBy(() -> this.service
			.reply(decides("bob", new ToolDecision(confirmation.calls().get(0).id(), true)))
			.blockLast()).isInstanceOf(IllegalStateException.class);
		assertThat(this.tools.cancelled).isEmpty();
		// And Alice can still decide.
		this.service.reply(decides("alice", new ToolDecision(confirmation.calls().get(0).id(), true))).blockLast();
		assertThat(this.tools.cancelled).containsExactly("alice cancelled A-2");
	}

	@Test
	void aDecisionRunsTheToolsOnce() {
		ChatReply.Confirmation confirmation = pause("Cancel A-2");
		ToolDecision allow = new ToolDecision(confirmation.calls().get(0).id(), true);
		this.service.reply(decides("alice", allow)).blockLast();

		// The paused reply is answered: a replay finds nothing that waits.
		assertThatThrownBy(() -> this.service.reply(decides("alice", allow)).blockLast())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Nothing in the conversation waits");
		assertThat(this.tools.cancelled).containsExactly("alice cancelled A-2");
	}

	@Test
	void aNewMessageInsteadOfADecisionClosesThePause() {
		ChatReply.Confirmation confirmation = pause("Cancel A-2");

		this.service.reply(says("Never mind, where is A-1?")).blockLast();

		assertThatThrownBy(() -> this.service
			.reply(decides("alice", new ToolDecision(confirmation.calls().get(0).id(), true)))
			.blockLast()).isInstanceOf(IllegalStateException.class);
		assertThat(this.tools.cancelled).isEmpty();
	}

	@Test
	void aNewMessageEndsTheReplyThatIsStillBeingWritten() {
		CompletableFuture<Void> ended = new CompletableFuture<>();
		beingWritten(ended);

		List<ChatReply> second = this.service.reply(says("Hello")).collectList().block();

		assertThat(ended).succeedsWithin(Duration.ofSeconds(5));
		assertThat(this.model.cancelled).hasValue(1);
		assertThat(second).containsExactly(new ChatReply.Text("Hi "), new ChatReply.Text("there"));
	}

	@Test
	void anotherConversationDoesNotEndIt() {
		beingWritten(new CompletableFuture<>());

		this.service.reply(new ChatRequest("c2", "Hello", () -> "alice")).blockLast();
		this.service.reply(new ChatRequest("c1", "Hello", () -> "bob")).blockLast();

		assertThat(this.model.cancelled).hasValue(0);
	}

	@Test
	void decisionsAreRefusedWhileAReplyIsBeingWritten() {
		beingWritten(new CompletableFuture<>());

		assertThatThrownBy(() -> this.service.reply(decides("alice", new ToolDecision("call_1", true))).blockLast())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("is being written");
		assertThat(this.model.cancelled).hasValue(0);
	}

	@Test
	void aReplyThatEndedLeavesNothingBehind() {
		this.service.reply(says("Hello")).blockLast();
		pause("Cancel A-2");

		assertThat((Map<?, ?>) ReflectionTestUtils.getField(this.service, "running")).isEmpty();
	}

	@Test
	void aToolThatNeedsNoConfirmationRunsAtOnce() {
		List<ChatReply> reply = this.service.reply(says("Where is A-1?")).collectList().block();

		assertThat(reply).containsExactly(new ChatReply.Text("Done: "), new ChatReply.Text("\"A-1 is packed\""));
	}

	@Test
	void aServiceWithoutSessionsCannotContinueAPausedReply() {
		ChatClientChatService withoutSessions = new ChatClientChatService(ChatClient.builder(this.model).build());

		assertThatThrownBy(() -> withoutSessions.reply(decides("alice", new ToolDecision("call_1", true))).blockLast())
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aReplyThatReachedTheLimitSaysSo() {
		List<ChatReply> reply = this.service.reply(says("Write a very long story")).collectList().block();

		assertThat(reply).containsExactly(new ChatReply.Text("Once "), new ChatReply.Text("upon "),
				new ChatReply.Ended(ChatReply.Reason.TRUNCATED));
	}

	@Test
	void aReplyThatTheModelFilteredSaysSo() {
		List<ChatReply> reply = this.service.reply(says("Say something forbidden")).collectList().block();

		assertThat(reply).containsExactly(new ChatReply.Ended(ChatReply.Reason.FILTERED));
	}

	@Test
	void aReplyThatEndedNormallyHasNoReason() {
		assertThat(this.service.reply(says("Hello")).collectList().block()).containsExactly(new ChatReply.Text("Hi "),
				new ChatReply.Text("there"));
	}

	/**
	 * Starts a reply of Alice's conversation that never ends by itself, and waits for
	 * its first words: the session is read on another thread before the model is asked.
	 * @param ended completes when the reply ends
	 */
	private void beingWritten(CompletableFuture<Void> ended) {
		List<ChatReply> reply = new CopyOnWriteArrayList<>();
		this.service.reply(says("Never stop"))
			.subscribe(reply::add, ended::completeExceptionally, () -> ended.complete(null));
		await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> assertThat(reply).containsExactly(new ChatReply.Text("and ")));
	}

	/**
	 * Runs a turn that must pause, and returns what it waits for.
	 */
	private ChatReply.Confirmation pause(String text) {
		List<ChatReply> reply = this.service.reply(says(text)).collectList().block();
		return (ChatReply.Confirmation) reply.get(reply.size() - 1);
	}

	static class OrderTools {

		final List<String> cancelled = new CopyOnWriteArrayList<>();

		@Tool(description = "Cancels an order of the signed-in user", requiresConfirmation = true)
		String cancelOrder(@ToolParam(description = "The order id") String orderId, ToolContext context) {
			String result = context.getContext().get("user") + " cancelled " + orderId;
			this.cancelled.add(result);
			return result;
		}

		@Tool(description = "Tells where an order is")
		String orderStatus(@ToolParam(description = "The order id") String orderId) {
			return orderId + " is packed";
		}

	}

	/**
	 * Asks for the tools by what the user says, and answers with what they returned.
	 */
	private static final class CancellingModel implements ChatModel {

		final List<Prompt> prompts = new CopyOnWriteArrayList<>();

		final AtomicInteger cancelled = new AtomicInteger();

		@Override
		public ChatOptions getOptions() {
			// With plain options the client leaves the tools out of the prompt.
			return ToolCallingChatOptions.builder().build();
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			this.prompts.add(prompt);
			Message last = prompt.getInstructions().getLast();
			if (last instanceof ToolResponseMessage results) {
				return text("stop", "Done: ", results.getResponses().get(0).responseData());
			}
			String said = ((UserMessage) last).getText();
			if (said.contains("Never stop")) {
				return Flux.concat(text(null, "and "), Flux.never()).doOnCancel(this.cancelled::incrementAndGet);
			}
			if (said.contains("Cancel")) {
				return toolCall("cancelOrder", "{\"orderId\":\"A-2\"}", "Let me cancel that. ");
			}
			if (said.contains("Where is")) {
				return toolCall("orderStatus", "{\"orderId\":\"A-1\"}", "");
			}
			if (said.contains("very long")) {
				return text("max_tokens", "Once ", "upon ");
			}
			if (said.contains("forbidden")) {
				return text("content_filter");
			}
			return text("stop", "Hi ", "there");
		}

		private static Flux<ChatResponse> text(String finishReason, String... words) {
			List<ChatResponse> chunks = new ArrayList<>();
			for (int i = 0; i < words.length; i++) {
				boolean last = i == words.length - 1;
				chunks.add(chunk(new AssistantMessage(words[i]), last ? finishReason : null));
			}
			if (words.length == 0) {
				chunks.add(chunk(new AssistantMessage(""), finishReason));
			}
			return Flux.fromIterable(chunks);
		}

		private static Flux<ChatResponse> toolCall(String tool, String arguments, String intro) {
			List<ChatResponse> chunks = new ArrayList<>();
			if (!intro.isEmpty()) {
				chunks.add(chunk(new AssistantMessage(intro), null));
			}
			AssistantMessage call = AssistantMessage.builder()
				.toolCalls(List.of(new AssistantMessage.ToolCall("call_" + tool, "function", tool, arguments)))
				.build();
			chunks.add(chunk(call, "tool_calls"));
			return Flux.fromIterable(chunks);
		}

		private static ChatResponse chunk(AssistantMessage message, String finishReason) {
			return new ChatResponse(List
				.of(new Generation(message, ChatGenerationMetadata.builder().finishReason(finishReason).build())));
		}

	}

}

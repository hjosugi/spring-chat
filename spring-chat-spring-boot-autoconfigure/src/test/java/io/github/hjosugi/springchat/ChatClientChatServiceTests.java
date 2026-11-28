package io.github.hjosugi.springchat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;

class ChatClientChatServiceTests {

	private final SessionService sessions = DefaultSessionService.builder()
		.sessionRepository(InMemorySessionRepository.builder().build())
		.build();

	private final ChatClientChatService service = new ChatClientChatService(
			ChatClient.builder(new EchoChatModel()).defaultAdvisors(SessionAdvisors.builder(this.sessions).build()).build());

	@Test
	void mediaWithOrWithoutTextReachesTheModelAndStaysWithItsUserMessage() {
		byte[] bytes = { 1, 2, 3 };
		Media image = Media.builder().name("photo.png").mimeType(MimeTypeUtils.IMAGE_PNG).data(bytes).build();
		List<Prompt> prompts = new ArrayList<>();
		var client = ChatClient.builder(new EchoChatModel() {
			@Override
			public Flux<ChatResponse> stream(Prompt prompt) {
				prompts.add(prompt);
				return super.stream(prompt);
			}
		}).defaultAdvisors(SessionAdvisors.builder(this.sessions).build()).build();
		var service = new ChatClientChatService(client);
		for (String text : List.of("", "look")) {
			service.reply(new ChatRequest("media", text, null, List.of(), List.of(image))).blockLast();
			UserMessage user = prompts.get(prompts.size() - 1).getUserMessage();
			assertThat(user.getText()).isEqualTo(text);
			assertThat(user.getMedia()).singleElement().satisfies((file) -> {
				assertThat(file.getName()).isEqualTo("photo.png");
				assertThat(file.getMimeType()).isEqualTo(MimeTypeUtils.IMAGE_PNG);
				assertThat(file.getDataAsByteArray()).containsExactly(bytes);
			});
		}
		service.reply(new ChatRequest("media", "again")).blockLast();
		assertThat(prompts.get(2).getInstructions()).filteredOn(UserMessage.class::isInstance)
			.map((message) -> ((UserMessage) message).getMedia()).containsExactly(List.of(image), List.of(image), List.of());
	}

	@Test
	void streamsTheModelsReplyAndRemembersTheConversation() {
		StepVerifier.create(this.service.stream(new ChatRequest("c1", "one"))).expectNext("1: ", "one").verifyComplete();
		StepVerifier.create(this.service.stream(new ChatRequest("c1", "two"))).expectNext("2: ", "two").verifyComplete();
		StepVerifier.create(this.service.stream(new ChatRequest("c2", "one"))).expectNext("1: ", "one").verifyComplete();
	}

	@Test
	void theBrowsersConversationIdCannotNameASessionOfTheApplication() {
		this.sessions.appendMessage(this.sessions.create(
				org.springframework.ai.session.CreateSessionRequest.builder().id("alice").build()).id(),
				new UserMessage("Something the application remembers about Alice"));

		StepVerifier.create(this.service.stream(new ChatRequest("alice", "What do you remember?")))
			.expectNext("1: ", "What do you remember?")
			.verifyComplete();

		assertThat(this.sessions.getMessages("alice")).hasSize(1);
		assertThat(this.sessions.getMessages(ChatClientChatService.sessionId(new ChatRequest("alice", "")))).hasSize(2);
	}

	@Test
	void twoUsersWhoSendTheSameConversationIdDoNotShareIt() {
		ChatRequest alice = new ChatRequest("c1", "one", () -> "alice");
		ChatRequest bob = new ChatRequest("c1", "one", () -> "bob");

		StepVerifier.create(this.service.stream(alice)).expectNext("1: ", "one").verifyComplete();
		StepVerifier.create(this.service.stream(new ChatRequest("c1", "two", () -> "alice")))
			.expectNext("2: ", "two")
			.verifyComplete();
		// Bob does not continue Alice's conversation, nor does someone who did not sign in.
		StepVerifier.create(this.service.stream(bob)).expectNext("1: ", "one").verifyComplete();
		StepVerifier.create(this.service.stream(new ChatRequest("c1", "one"))).expectNext("1: ", "one").verifyComplete();

		assertThat(this.sessions.getMessages(ChatClientChatService.sessionId(alice))).hasSize(4);
		assertThat(this.sessions.getMessages(ChatClientChatService.sessionId(bob))).hasSize(2);
	}

	@Test
	void aUserNameCannotImitateAnotherUsersSession() {
		// Neither "a:b" with the conversation "c" nor "a" with "b:c" may end up in one session.
		assertThat(ChatClientChatService.sessionId(new ChatRequest("c", "x", () -> "a:b")))
			.isNotEqualTo(ChatClientChatService.sessionId(new ChatRequest("b:c", "x", () -> "a")));
		assertThat(ChatClientChatService.sessionId(new ChatRequest("c", "x", () -> "anonymous")))
			.isNotEqualTo(ChatClientChatService.sessionId(new ChatRequest("c", "x")));
	}

	@Test
	void theIdOfASessionDoesNotShowWhoOwnsIt() {
		String id = ChatClientChatService.sessionId(new ChatRequest("c1", "x", () -> "alice@example.com"));

		assertThat(id).startsWith("spring-chat:user-").endsWith(":c1").doesNotContain("alice");
	}

	@Test
	void aSessionRecordsWhoStartedIt() {
		ChatRequest alice = new ChatRequest("c1", "one", () -> "alice");
		StepVerifier.create(this.service.stream(alice)).expectNext("1: ", "one").verifyComplete();

		Session session = this.sessions.findById(ChatClientChatService.sessionId(alice));

		assertThat(SessionAttributes.principalName(session)).isEqualTo("alice");
		assertThat(this.sessions.findByMetadata(SessionAttributes.PRINCIPAL_NAME, "alice")).hasSize(1);
	}

	@Test
	void aReplySeesTheLatestMessagesOnly() {
		ChatClientChatService service = new ChatClientChatService(ChatClient.builder(new EchoChatModel())
			.defaultAdvisors(SessionAdvisors.builder(this.sessions)
				.memory((memory) -> memory.eventFilter(EventFilter.lastN(2)))
				.build())
			.build());

		for (String text : List.of("one", "two", "three")) {
			StepVerifier.create(service.stream(new ChatRequest("c1", text))).expectNextCount(2).verifyComplete();
		}

		// The two before it, which are the user's message and the reply of the turn before, and its own.
		assertThat(EchoChatModel.lastUserMessages).isEqualTo(2L);
		assertThat(this.sessions.getMessages(ChatClientChatService.sessionId(new ChatRequest("c1", "")))).hasSize(6);
	}

	@Test
	void customizersAddToEachPromptInOrderAndSeeWhoAsked() {
		List<String> calls = new ArrayList<>();
		ChatPromptCustomizer first = (prompt, request) -> {
			calls.add("first " + request.principal().getName());
			prompt.system("Answer briefly.");
		};
		ChatPromptCustomizer second = (prompt, request) -> calls.add("second");
		ChatClientChatService service = new ChatClientChatService(ChatClient.builder(new EchoChatModel())
			.defaultAdvisors(SessionAdvisors.builder(this.sessions).build())
			.build(), List.of(first, second));

		StepVerifier.create(service.stream(new ChatRequest("c1", "one", () -> "alice")))
			.expectNext("1: ", "one")
			.verifyComplete();

		assertThat(calls).containsExactly("first alice", "second");
		// The memory still applies: the customizer added to the prompt and took nothing away.
		assertThat(this.sessions.getMessages(ChatClientChatService.sessionId(new ChatRequest("c1", "", () -> "alice"))))
			.hasSize(2);
		assertThat(EchoChatModel.lastSystem).isEqualTo("Answer briefly.");
	}

	/**
	 * Answers with the number of user messages it was given, then the last one.
	 */
	private static class EchoChatModel implements ChatModel {

		@Override
		public ChatResponse call(Prompt prompt) {
			throw new UnsupportedOperationException();
		}

		static volatile String lastSystem;

		static volatile long lastUserMessages;

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			lastSystem = prompt.getSystemMessage().getText();
			long userMessages = prompt.getInstructions().stream().filter(UserMessage.class::isInstance).count();
			lastUserMessages = userMessages;
			return Flux.just(userMessages + ": ", prompt.getUserMessage().getText())
				.map((text) -> new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
		}

	}

}

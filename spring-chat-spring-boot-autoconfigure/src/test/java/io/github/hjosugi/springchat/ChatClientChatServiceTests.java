package io.github.hjosugi.springchat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;

class ChatClientChatServiceTests {

	private final ChatMemory memory = MessageWindowChatMemory.builder().build();

	private final ChatClientChatService service = new ChatClientChatService(ChatClient.builder(new EchoChatModel())
		.defaultAdvisors(MessageChatMemoryAdvisor.builder(this.memory).build())
		.build());

	@Test
	void streamsTheModelsReplyAndRemembersTheConversation() {
		StepVerifier.create(this.service.stream(new ChatRequest("c1", "one"))).expectNext("1: ", "one").verifyComplete();
		StepVerifier.create(this.service.stream(new ChatRequest("c1", "two"))).expectNext("2: ", "two").verifyComplete();
		StepVerifier.create(this.service.stream(new ChatRequest("c2", "one"))).expectNext("1: ", "one").verifyComplete();
	}

	@Test
	void theBrowsersConversationIdCannotNameAConversationOfTheApplication() {
		this.memory.add("alice", new UserMessage("Something the application remembers about Alice"));

		StepVerifier.create(this.service.stream(new ChatRequest("alice", "What do you remember?")))
			.expectNext("1: ", "What do you remember?")
			.verifyComplete();

		assertThat(this.memory.get("alice")).hasSize(1);
		assertThat(this.memory.get(ChatClientChatService.CONVERSATION_PREFIX + "alice")).hasSize(2);
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
			.defaultAdvisors(MessageChatMemoryAdvisor.builder(this.memory).build())
			.build(), List.of(first, second));

		StepVerifier.create(service.stream(new ChatRequest("c1", "one", () -> "alice")))
			.expectNext("1: ", "one")
			.verifyComplete();

		assertThat(calls).containsExactly("first alice", "second");
		// The memory still applies: the customizer added to the prompt and took nothing away.
		assertThat(this.memory.get(ChatClientChatService.CONVERSATION_PREFIX + "c1")).hasSize(2);
		assertThat(EchoChatModel.lastSystem).isEqualTo("Answer briefly.");
	}

	/**
	 * Answers with the number of user messages it was given, then the last one.
	 */
	private static final class EchoChatModel implements ChatModel {

		@Override
		public ChatResponse call(Prompt prompt) {
			throw new UnsupportedOperationException();
		}

		static volatile String lastSystem;

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			lastSystem = prompt.getSystemMessage().getText();
			long userMessages = prompt.getInstructions().stream().filter(UserMessage.class::isInstance).count();
			return Flux.just(userMessages + ": ", prompt.getUserMessage().getText())
				.map((text) -> new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
		}

	}

}

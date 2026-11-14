package io.github.hjosugi.springchat;

import java.util.List;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

/**
 * A {@link ChatService} that asks a {@link ChatClient}, the default one.
 */
public class ChatClientChatService implements ChatService {

	/**
	 * The browser chooses its conversation id. The prefix keeps it from naming a
	 * conversation that the application keeps in the same {@link ChatMemory}.
	 */
	static final String CONVERSATION_PREFIX = "spring-chat:";

	private final ChatClient chatClient;

	private final List<ChatPromptCustomizer> customizers;

	/**
	 * A service that adds nothing to the prompts.
	 * @param chatClient asks the model; its defaults hold the system prompt and memory
	 */
	public ChatClientChatService(ChatClient chatClient) {
		this(chatClient, List.of());
	}

	/**
	 * A service that lets customizers add to each prompt.
	 * @param chatClient asks the model; its defaults hold the system prompt and memory
	 * @param customizers add to each prompt, in this order
	 */
	public ChatClientChatService(ChatClient chatClient, List<ChatPromptCustomizer> customizers) {
		this.chatClient = chatClient;
		this.customizers = List.copyOf(customizers);
	}

	@Override
	public Flux<String> stream(ChatRequest request) {
		String conversationId = CONVERSATION_PREFIX + request.conversationId();
		ChatClient.ChatClientRequestSpec prompt = this.chatClient.prompt()
			.user(request.text())
			.advisors((advisors) -> advisors.param(ChatMemory.CONVERSATION_ID, conversationId));
		this.customizers.forEach((customizer) -> customizer.customize(prompt, request));
		return prompt.stream().content();
	}

}

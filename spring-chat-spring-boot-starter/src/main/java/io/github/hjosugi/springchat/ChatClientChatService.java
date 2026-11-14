package io.github.hjosugi.springchat;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

/**
 * A {@link ChatService} that asks a {@link ChatClient}.
 */
public class ChatClientChatService implements ChatService {

	/**
	 * The browser chooses its conversation id. The prefix keeps it from naming a
	 * conversation that the application keeps in the same {@link ChatMemory}.
	 */
	static final String CONVERSATION_PREFIX = "spring-chat:";

	private final ChatClient chatClient;

	public ChatClientChatService(ChatClient chatClient) {
		this.chatClient = chatClient;
	}

	@Override
	public Flux<String> stream(ChatRequest request) {
		String conversationId = CONVERSATION_PREFIX + request.conversationId();
		return this.chatClient.prompt()
			.user(request.text())
			.advisors((advisors) -> advisors.param(ChatMemory.CONVERSATION_ID, conversationId))
			.stream()
			.content();
	}

}

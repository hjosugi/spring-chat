package io.github.hjosugi.springchat;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

/**
 * A {@link ChatService} that asks a {@link ChatClient}.
 */
public class ChatClientChatService implements ChatService {

	private final ChatClient chatClient;

	public ChatClientChatService(ChatClient chatClient) {
		this.chatClient = chatClient;
	}

	@Override
	public Flux<String> stream(ChatRequest request) {
		return this.chatClient.prompt()
			.user(request.text())
			.advisors((advisors) -> advisors.param(ChatMemory.CONVERSATION_ID, request.conversationId()))
			.stream()
			.content();
	}

}

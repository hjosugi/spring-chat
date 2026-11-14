package io.github.hjosugi.springchat;

import reactor.core.publisher.Flux;

/**
 * Answers a chat message. Declare a bean of this type to replace the default,
 * which asks the application's {@link org.springframework.ai.chat.client.ChatClient}.
 */
@FunctionalInterface
public interface ChatService {

	/**
	 * Streams the reply as pieces of text, in order.
	 */
	Flux<String> stream(ChatRequest request);

}

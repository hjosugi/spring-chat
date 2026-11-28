package io.github.hjosugi.springchat;

import reactor.core.publisher.Flux;

/**
 * Answers a chat message. Declare a bean of this type to replace the default, which
 * asks the application's {@link org.springframework.ai.chat.client.ChatClient}. To add
 * tools or advisors to the default instead, declare a {@link ChatPromptCustomizer}.
 */
@FunctionalInterface
public interface ChatService {

	/**
	 * Streams the reply as pieces of text, in order. The chat cancels the stream when
	 * the user stops the reply or leaves.
	 * @param request the message, and who sent it
	 * @return the pieces of the reply
	 */
	Flux<String> stream(ChatRequest request);

	/**
	 * Streams the text, progress, usage and outcome of a reply. The chat asks this one.
	 * It is {@link #stream} by default, which has only
	 * text. The default service overrides it.
	 * @param request the message, or the decisions on a paused reply, and who sent it
	 * @return the pieces of the reply
	 */
	default Flux<ChatReply> reply(ChatRequest request) {
		return stream(request).map(ChatReply.Text::new);
	}

}

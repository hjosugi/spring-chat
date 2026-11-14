package io.github.hjosugi.springchat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for spring-chat.
 *
 * @param enabled whether to configure the chat
 * @param path where the chat lives: the page at this path, SSE at {@code /stream} and
 * WebSocket at {@code /ws} below it
 * @param transport how the chat UI receives replies
 * @param title heading of the built-in page
 * @param intro first message shown in an empty chat
 * @param placeholder placeholder of the message input
 * @param systemPrompt system prompt of the default chat service
 * @param maxMessageLength longest accepted message, in characters
 * @param page the built-in page
 * @param memory the conversation memory of the default chat service
 */
@ConfigurationProperties("spring-chat")
public record ChatProperties(@DefaultValue("true") boolean enabled, @DefaultValue("/chat") String path,
		@DefaultValue("sse") ChatTransport transport, @DefaultValue("Chat") String title, String intro,
		@DefaultValue("Message") String placeholder, String systemPrompt, @DefaultValue("8000") int maxMessageLength,
		@DefaultValue Page page, @DefaultValue Memory memory) {

	public ChatProperties {
		if (!path.startsWith("/") || path.endsWith("/")) {
			throw new IllegalArgumentException("spring-chat.path must start with '/' and not end with one: " + path);
		}
	}

	/**
	 * Path of the SSE endpoint.
	 */
	public String streamPath() {
		return this.path + "/stream";
	}

	/**
	 * Path of the WebSocket endpoint.
	 */
	public String socketPath() {
		return this.path + "/ws";
	}

	/**
	 * @param enabled whether to serve the built-in page at {@code spring-chat.path}
	 */
	public record Page(@DefaultValue("true") boolean enabled) {
	}

	/**
	 * @param enabled whether replies see the earlier messages of their conversation
	 */
	public record Memory(@DefaultValue("true") boolean enabled) {
	}

}

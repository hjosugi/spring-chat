package io.github.hjosugi.springchat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for spring-chat.
 *
 * @param enabled Whether to configure the chat.
 * @param path Where the chat is: the built-in page at this path, SSE at /stream and
 * WebSocket at /ws below it.
 * @param transport How the chat UI receives replies.
 * @param title Heading of the built-in page.
 * @param intro First message shown in an empty chat.
 * @param placeholder Placeholder of the message input.
 * @param systemPrompt System prompt of the default chat service.
 * @param maxMessageLength Longest accepted message, in characters. Over WebSocket on
 * WebFlux a message must also fit one frame, 64 KB unless the server is configured
 * otherwise.
 * @param page The built-in page.
 * @param memory The conversation memory of the default chat service.
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
		if (maxMessageLength < 1) {
			throw new IllegalArgumentException("spring-chat.max-message-length must be positive: " + maxMessageLength);
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
	 * @param enabled Whether to serve the built-in page at spring-chat.path.
	 */
	public record Page(@DefaultValue("true") boolean enabled) {
	}

	/**
	 * @param enabled Whether replies see the earlier messages of their conversation.
	 */
	public record Memory(@DefaultValue("true") boolean enabled) {
	}

}

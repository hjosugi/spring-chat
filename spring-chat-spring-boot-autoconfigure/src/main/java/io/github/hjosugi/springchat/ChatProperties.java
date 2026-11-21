package io.github.hjosugi.springchat;

import java.time.Duration;

import org.jspecify.annotations.Nullable;

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
		@DefaultValue("sse") ChatTransport transport, @DefaultValue("Chat") String title, @Nullable String intro,
		@DefaultValue("Message") String placeholder, @Nullable String systemPrompt,
		@DefaultValue("8000") int maxMessageLength,
		@DefaultValue Page page, @DefaultValue Memory memory) {

	/**
	 * Properties that the chat can be mapped and run with.
	 * @throws IllegalArgumentException if the path does not start with a slash or ends
	 * with one, or if the longest message is not positive
	 */
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
	 * @return the path, below {@link #path()}
	 */
	public String streamPath() {
		return this.path + "/stream";
	}

	/**
	 * Path of the WebSocket endpoint.
	 * @return the path, below {@link #path()}
	 */
	public String socketPath() {
		return this.path + "/ws";
	}

	/**
	 * The built-in page.
	 *
	 * @param enabled Whether to serve the built-in page at spring-chat.path.
	 */
	public record Page(@DefaultValue("true") boolean enabled) {
	}

	/**
	 * The conversation memory of the default chat service.
	 *
	 * @param enabled Whether replies see the earlier messages of their conversation.
	 * @param maxMessages How many of the latest messages a reply sees, a whole turn
	 * more at most; 0 for all of them.
	 * @param timeToLive How long a conversation is kept after it was started, when the
	 * application has no session service of its own.
	 */
	public record Memory(@DefaultValue("true") boolean enabled, @DefaultValue("20") int maxMessages,
			@DefaultValue("24h") Duration timeToLive) {

		/**
		 * A memory that the chat can run with.
		 * @throws IllegalArgumentException if the number of messages is negative or the
		 * time to live is not positive
		 */
		public Memory {
			if (maxMessages < 0) {
				throw new IllegalArgumentException(
						"spring-chat.memory.max-messages must not be negative: " + maxMessages);
			}
			if (timeToLive.isNegative() || timeToLive.isZero()) {
				throw new IllegalArgumentException("spring-chat.memory.time-to-live must be positive: " + timeToLive);
			}
		}

	}

}

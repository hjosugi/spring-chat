package io.github.hjosugi.springchat.web;

import io.github.hjosugi.springchat.ChatProperties;

/**
 * What the {@code spring-chat/chat} fragment renders from, in every model as {@code springChat}.
 *
 * @param title heading of the built-in page
 * @param transport {@code sse} or {@code websocket}
 * @param streamPath path of the SSE endpoint
 * @param socketPath path of the WebSocket endpoint
 * @param intro first message shown in an empty chat, or {@code null}
 * @param placeholder placeholder of the message input
 */
public record ChatView(String title, String transport, String streamPath, String socketPath, String intro,
		String placeholder) {

	public static ChatView of(ChatProperties properties) {
		return new ChatView(properties.title(), properties.transport().name().toLowerCase(), properties.streamPath(),
				properties.socketPath(), properties.intro(), properties.placeholder());
	}

}

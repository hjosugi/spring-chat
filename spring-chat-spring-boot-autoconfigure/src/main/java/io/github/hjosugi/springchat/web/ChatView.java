package io.github.hjosugi.springchat.web;

import java.util.Locale;
import java.util.stream.Collectors;

import io.github.hjosugi.springchat.AttachmentProperties;
import io.github.hjosugi.springchat.ChatMessages;
import io.github.hjosugi.springchat.ChatProperties;
import org.jspecify.annotations.Nullable;

/**
 * What the chat's templates render from. It is the bean {@code springChat}, so a
 * template reads it as {@code ${@springChat.title}} whatever its model holds.
 *
 * @param title heading of the built-in page
 * @param transport {@code sse} or {@code websocket}
 * @param streamPath path of the SSE endpoint
 * @param socketPath path of the WebSocket endpoint
 * @param intro first message shown in an empty chat
 * @param placeholder placeholder of the message input
 * @param messages labels and status messages shown by the page
 * @param attachments limits for file selection
 */
public record ChatView(String title, String transport, String streamPath, String socketPath, @Nullable String intro,
		String placeholder, ChatMessages messages, Attachments attachments) {

	public static ChatView of(ChatProperties properties, AttachmentProperties attachments, ChatMessages messages) {
		return new ChatView(properties.title(), properties.transport().name().toLowerCase(Locale.ROOT),
				properties.streamPath(), properties.socketPath(), properties.intro(), properties.placeholder(),
				messages, new Attachments(attachments.enabled(), attachments.maxCount(), attachments.maxSize().toBytes(),
						attachments.allowedTypes().stream().map(Object::toString).collect(Collectors.joining(","))));
	}

	public record Attachments(boolean enabled, int maxCount, long maxSize, String acceptedTypes) {
	}

}

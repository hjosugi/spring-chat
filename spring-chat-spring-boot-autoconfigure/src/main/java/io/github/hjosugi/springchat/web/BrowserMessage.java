package io.github.hjosugi.springchat.web;

import org.jspecify.annotations.Nullable;

/**
 * What the browser sends, as it sent it: any part may be missing. Over SSE it is the
 * request body and has no type. Over WebSocket {@code message} asks for a reply and
 * {@code stop} ends the running one.
 *
 * @param type {@code message}, {@code stop} or nothing
 * @param conversationId see {@link io.github.hjosugi.springchat.ChatRequest}
 * @param text see {@link io.github.hjosugi.springchat.ChatRequest}
 */
public record BrowserMessage(@Nullable String type, @Nullable String conversationId, @Nullable String text) {

	static final BrowserMessage EMPTY = new BrowserMessage(null, null, null);

	public boolean isStop() {
		return "stop".equals(this.type);
	}

}

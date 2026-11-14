package io.github.hjosugi.springchat.web;

import io.github.hjosugi.springchat.ChatRequest;

/**
 * What the browser sends. Over SSE it is the request body and has no type. Over
 * WebSocket {@code message} asks for a reply and {@code stop} ends the running one.
 *
 * @param type {@code message}, {@code stop} or nothing
 * @param conversationId see {@link ChatRequest}
 * @param text see {@link ChatRequest}
 */
public record BrowserMessage(String type, String conversationId, String text) {

	public boolean isStop() {
		return "stop".equals(this.type);
	}

	public ChatRequest toRequest() {
		return new ChatRequest(this.conversationId, this.text);
	}

}

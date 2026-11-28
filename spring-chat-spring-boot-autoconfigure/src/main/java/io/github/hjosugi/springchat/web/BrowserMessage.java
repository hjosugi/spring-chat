package io.github.hjosugi.springchat.web;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * What the browser sends, as it sent it: any part may be missing. Over SSE it is the
 * request body and has no type. Over WebSocket {@code message} asks for a reply and
 * {@code stop} ends the running one.
 * <p>
 * A message has a text, or decisions: the user's answers to the tool calls that a
 * reply waits for.
 *
 * @param type {@code message}, {@code stop} or nothing
 * @param conversationId see {@link io.github.hjosugi.springchat.ChatRequest}
 * @param text see {@link io.github.hjosugi.springchat.ChatRequest}
 * @param decisions see {@link io.github.hjosugi.springchat.ChatRequest}
 * @param attachments optional named files with base64 data
 */
public record BrowserMessage(@Nullable String type, @Nullable String conversationId, @Nullable String text,
		@Nullable List<Decision> decisions, @Nullable List<Attachment> attachments) {

	static final BrowserMessage EMPTY = new BrowserMessage(null, null, null, null);

	public BrowserMessage(@Nullable String type, @Nullable String conversationId, @Nullable String text,
			@Nullable List<Decision> decisions) {
		this(type, conversationId, text, decisions, null);
	}

	public BrowserMessage(@Nullable String type, @Nullable String conversationId, @Nullable String text) {
		this(type, conversationId, text, null);
	}

	public boolean isStop() {
		return "stop".equals(this.type);
	}

	/**
	 * One answer, as the browser sent it.
	 *
	 * @param id the tool call
	 * @param allow whether it may run
	 */
	public record Decision(@Nullable String id, boolean allow) {
	}

	public record Attachment(@Nullable String name, @Nullable String mediaType, @Nullable String data) {
	}

}

package io.github.hjosugi.springchat.web;

import org.jspecify.annotations.Nullable;

/**
 * What the server sends during a reply, over SSE and WebSocket alike: {@code start},
 * then {@code text} as the reply arrives and {@code ping} while it is quiet, then
 * {@code done} or {@code error}.
 *
 * @param type one of the constants
 * @param delta the next piece of the reply, for {@code text}
 * @param message what went wrong, for {@code error}
 */
public record ChatEvent(String type, @Nullable String delta, @Nullable String message) {

	public static final String START = "start";

	public static final String TEXT = "text";

	public static final String PING = "ping";

	public static final String DONE = "done";

	public static final String ERROR = "error";

	public static ChatEvent start() {
		return new ChatEvent(START, null, null);
	}

	public static ChatEvent text(String delta) {
		return new ChatEvent(TEXT, delta, null);
	}

	public static ChatEvent ping() {
		return new ChatEvent(PING, null, null);
	}

	public static ChatEvent done() {
		return new ChatEvent(DONE, null, null);
	}

	public static ChatEvent error(String message) {
		return new ChatEvent(ERROR, null, message);
	}

	/**
	 * Whether this event ends its reply.
	 */
	public boolean endsReply() {
		return DONE.equals(this.type) || ERROR.equals(this.type);
	}

}

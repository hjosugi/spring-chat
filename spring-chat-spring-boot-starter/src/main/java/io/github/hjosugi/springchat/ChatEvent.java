package io.github.hjosugi.springchat;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What the server sends during a reply, over SSE and WebSocket alike:
 * {@code start}, any number of {@code text}, then {@code done} or {@code error}.
 *
 * @param type one of the {@code TYPE} constants
 * @param delta the next piece of the reply, for {@code text}
 * @param message what went wrong, for {@code error}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatEvent(String type, String delta, String message) {

	public static final String START = "start";

	public static final String TEXT = "text";

	public static final String DONE = "done";

	public static final String ERROR = "error";

	public static ChatEvent start() {
		return new ChatEvent(START, null, null);
	}

	public static ChatEvent text(String delta) {
		return new ChatEvent(TEXT, delta, null);
	}

	public static ChatEvent done() {
		return new ChatEvent(DONE, null, null);
	}

	public static ChatEvent error(String message) {
		return new ChatEvent(ERROR, null, message);
	}

}

package io.github.hjosugi.springchat.web;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * What the server sends during a reply, over SSE and WebSocket alike: {@code start},
 * then {@code text} as the reply arrives and {@code ping} while it is quiet, then
 * {@code done}, {@code confirm} or {@code error}.
 * <p>
 * {@code confirm} ends a reply that paused: the model asked for tool calls that the
 * user has to allow, and none has run. The browser answers with a message that has
 * decisions in place of a text, and gets the rest of the reply.
 *
 * @param type one of the constants
 * @param delta the next piece of the reply, for {@code text}
 * @param message what went wrong, for {@code error}
 * @param reason {@code truncated} or {@code filtered}, for a {@code done} that is not
 * the model's end
 * @param calls the tool calls that wait for a decision, for {@code confirm}
 */
public record ChatEvent(String type, @Nullable String delta, @Nullable String message, @Nullable String reason,
		@Nullable List<Call> calls) {

	public static final String START = "start";

	public static final String TEXT = "text";

	public static final String PING = "ping";

	public static final String DONE = "done";

	public static final String ERROR = "error";

	public static final String CONFIRM = "confirm";

	public static ChatEvent start() {
		return new ChatEvent(START, null, null, null, null);
	}

	public static ChatEvent text(String delta) {
		return new ChatEvent(TEXT, delta, null, null, null);
	}

	public static ChatEvent ping() {
		return new ChatEvent(PING, null, null, null, null);
	}

	public static ChatEvent done() {
		return new ChatEvent(DONE, null, null, null, null);
	}

	/**
	 * The end of a reply that the model did not finish.
	 * @param reason {@code truncated} or {@code filtered}
	 */
	public static ChatEvent done(String reason) {
		return new ChatEvent(DONE, null, null, reason, null);
	}

	/**
	 * The end of a reply that waits for the user to allow tool calls.
	 */
	public static ChatEvent confirm(List<Call> calls) {
		return new ChatEvent(CONFIRM, null, null, null, calls);
	}

	public static ChatEvent error(String message) {
		return new ChatEvent(ERROR, null, message, null, null);
	}

	/**
	 * Whether this event ends its reply.
	 */
	public boolean endsReply() {
		return DONE.equals(this.type) || ERROR.equals(this.type) || CONFIRM.equals(this.type);
	}

	/**
	 * A tool call that waits for a decision.
	 *
	 * @param id names the call in the decisions
	 * @param name the tool
	 * @param arguments what the model passes it, as JSON
	 */
	public record Call(String id, String name, String arguments) {
	}

}

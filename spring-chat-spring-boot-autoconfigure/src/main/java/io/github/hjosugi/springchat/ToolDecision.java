package io.github.hjosugi.springchat;

import java.util.Objects;

/**
 * The user's answer to one tool call of a {@link ChatReply.Confirmation}.
 *
 * @param id the call, as {@link ChatReply.PendingToolCall#id()} named it
 * @param allow whether the call may run
 */
public record ToolDecision(String id, boolean allow) {

	public ToolDecision {
		Objects.requireNonNull(id, "id");
	}

}

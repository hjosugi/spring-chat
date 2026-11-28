package io.github.hjosugi.springchat;

import java.security.Principal;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.content.Media;

/**
 * One user message, or the user's decision on the tool calls that a reply waits for.
 *
 * @param conversationId names the conversation whose earlier messages the reply should
 * see. The browser makes it up, so it is not a secret and not proof of who sent it
 * @param text the message; empty for a request that brings decisions
 * @param principal who sent it, as the web stack knows them (with Spring Security, the
 * {@code Authentication}), or {@code null} if they did not sign in
 * @param decisions the answers to the tool calls of a {@link ChatReply.Confirmation}.
 * With them the request continues the paused reply of its conversation, and has no text
 * @param attachments the named media sent with this message, with their MIME types and bytes
 */
public record ChatRequest(String conversationId, String text, @Nullable Principal principal,
		List<ToolDecision> decisions, List<Media> attachments) {

	/**
	 * A message or decisions from someone.
	 * @throws NullPointerException if the conversation id, the text or the decisions are
	 * {@code null}
	 */
	public ChatRequest {
		Objects.requireNonNull(conversationId, "conversationId");
		Objects.requireNonNull(text, "text");
		decisions = List.copyOf(decisions);
		attachments = List.copyOf(attachments);
	}

	/** A text message or a decision without attachments. */
	public ChatRequest(String conversationId, String text, @Nullable Principal principal, List<ToolDecision> decisions) {
		this(conversationId, text, principal, decisions, List.of());
	}

	/**
	 * A message from someone.
	 * @param conversationId names the conversation
	 * @param text the message
	 * @param principal who sent it, or {@code null}
	 */
	public ChatRequest(String conversationId, String text, @Nullable Principal principal) {
		this(conversationId, text, principal, List.of());
	}

	/**
	 * A message from nobody in particular.
	 * @param conversationId names the conversation
	 * @param text the message
	 */
	public ChatRequest(String conversationId, String text) {
		this(conversationId, text, null);
	}

}

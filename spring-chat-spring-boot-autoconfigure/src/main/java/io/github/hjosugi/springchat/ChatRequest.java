package io.github.hjosugi.springchat;

import java.security.Principal;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * One user message.
 *
 * @param conversationId names the conversation whose earlier messages the reply should
 * see. The browser makes it up, so it is not a secret and not proof of who sent it
 * @param text the message
 * @param principal who sent it, as the web stack knows them (with Spring Security, the
 * {@code Authentication}), or {@code null} if they did not sign in
 */
public record ChatRequest(String conversationId, String text, @Nullable Principal principal) {

	/**
	 * A message from someone.
	 * @throws NullPointerException if the conversation id or the text is {@code null}
	 */
	public ChatRequest {
		Objects.requireNonNull(conversationId, "conversationId");
		Objects.requireNonNull(text, "text");
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

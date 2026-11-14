package io.github.hjosugi.springchat.web;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Adds the {@link ChatView} to every model, so any template can include the chat fragment.
 */
@ControllerAdvice
public class ChatViewAdvice {

	private final ChatView view;

	public ChatViewAdvice(ChatView view) {
		this.view = view;
	}

	@ModelAttribute("springChat")
	public ChatView springChat() {
		return this.view;
	}

}

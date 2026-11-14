package io.github.hjosugi.springchat.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the built-in chat page, at {@code spring-chat.path}.
 */
@Controller
public class ChatPageController {

	@GetMapping
	public String chat() {
		return "spring-chat/index";
	}

}

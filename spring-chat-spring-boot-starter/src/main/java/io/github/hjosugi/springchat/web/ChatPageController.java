package io.github.hjosugi.springchat.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the built-in chat page.
 */
@Controller
public class ChatPageController {

	@GetMapping("${spring-chat.path:/chat}")
	public String chat() {
		return "spring-chat/index";
	}

}

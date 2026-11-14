package io.github.hjosugi.springchat.sample;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The application's own page, with the chat fragment in it. The built-in page is at /chat.
 */
@Controller
class HomeController {

	@GetMapping("/")
	String home(@RequestParam(defaultValue = "sse") String transport, Model model) {
		model.addAttribute("transport", "websocket".equals(transport) ? "websocket" : "sse");
		return "home";
	}

}

package io.github.hjosugi.springchat.sample

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * The application's own page, with the chat fragment in it. The built-in page is at /chat.
 */
@Controller
class HomeController {

    @GetMapping("/")
    fun home(@RequestParam(defaultValue = "sse") transport: String, model: Model): String {
        model.addAttribute("transport", if (transport == "websocket") "websocket" else "sse")
        return "home"
    }
}

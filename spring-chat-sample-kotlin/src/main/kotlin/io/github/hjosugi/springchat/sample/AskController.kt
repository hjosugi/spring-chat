package io.github.hjosugi.springchat.sample

import io.github.hjosugi.springchat.ChatReply
import io.github.hjosugi.springchat.ChatRequest
import io.github.hjosugi.springchat.ChatService
import io.github.hjosugi.springchat.complete
import io.github.hjosugi.springchat.replyFlow
import io.github.hjosugi.springchat.streamFlow
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Endpoints for a REST client or another service, which ask the application's [ChatService]
 * through the Kotlin extensions of spring-chat-kotlin. The chat page has its own endpoints. A
 * request names its conversation, or has one of its own.
 */
@RestController
@RequestMapping("/ask")
class AskController(private val chat: ChatService) {

    /** The whole reply, once it is written. A suspend function: WebFlux does not block a thread to wait. */
    @GetMapping
    suspend fun complete(@RequestParam text: String, @RequestParam(required = false) conversation: String?): String =
        chat.complete(request(conversation, text))

    /** The reply as server-sent events, one for each piece of text, as the model writes it. */
    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@RequestParam text: String, @RequestParam(required = false) conversation: String?): Flow<String> =
        chat.streamFlow(request(conversation, text))

    /** The reply with a note where it did not end the way a reply does, written by [describe]. */
    @GetMapping("/replies")
    suspend fun replies(@RequestParam text: String, @RequestParam(required = false) conversation: String?): String =
        chat.replyFlow(request(conversation, text)).toList().joinToString("") { it.describe() }

    private fun request(conversation: String?, text: String) =
        ChatRequest(conversation ?: UUID.randomUUID().toString(), text)
}

/**
 * Each kind of reply as text. There is no `else`: the compiler knows the subtypes of the
 * sealed [ChatReply], so a kind that a later version adds fails this function's build and
 * is not forgotten.
 */
internal fun ChatReply.describe(): String = when (this) {
    is ChatReply.Text -> delta
    is ChatReply.Confirmation -> "\n[waits for you to allow: ${calls.joinToString { it.name }}]"
    is ChatReply.Ended -> "\n[ended early: ${reason.name.lowercase()}]"
}

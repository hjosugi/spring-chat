package io.github.hjosugi.springchat.sample

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

/**
 * The chat's own page and endpoint, in a Kotlin application: what the starter brings, with the
 * customizer lambda and the demo model of the sample.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SampleApplicationTests(@LocalServerPort port: Int) {

    private val client = WebClient.create("http://localhost:$port")

    @Test
    fun pagesRenderTheChat() = runBlocking<Unit> {
        assertThat(get("/chat")).contains(
            "<deep-chat", "data-transport=\"sse\"", "data-stream-url=\"/chat/stream\"", "/dist/deepChat.bundle.js\"",
        )
        assertThat(get("/?transport=websocket")).contains(
            "spring-chat Kotlin sample", "data-transport=\"websocket\"", "data-socket-url=\"/chat/ws\"",
        )
        assertThat(get("/spring-chat/spring-chat.js")).contains("deep-chat.spring-chat")
    }

    @Test
    fun theChatsOwnEndpointStreamsTheReplyAndOffersTheCustomizersTool() = runBlocking<Unit> {
        val events = within {
            client.post().uri("/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"conversationId": "chat-1", "text": "hello there"}""")
                .retrieve().awaitBody<String>()
        }

        assertThat(events).startsWith("event:start")
            .contains("event:text", "\"delta\":\"**hello \"")
            .contains("event:done")
            .doesNotContain("event:error")
        // The chat sends a word in each event: the text of the reply is the deltas, put together.
        val text = DELTA.findAll(events).joinToString("") { it.groupValues[1] }
        // The ChatPromptCustomizer of the application is a lambda, and the model was offered its tool.
        assertThat(text).contains("Tools offered: now.")
    }

    private companion object {
        val DELTA = Regex("\"delta\":\"([^\"]*)\"")
    }

    private suspend fun get(path: String): String = within { client.get().uri(path).retrieve().awaitBody<String>() }

    /** With a limit, so that a reply that never ends fails its test and does not hold the build. */
    private suspend fun <T> within(block: suspend () -> T): T = withTimeout(30.seconds) { block() }
}

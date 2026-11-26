package io.github.hjosugi.springchat.sample

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.toList
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
import org.springframework.web.reactive.function.client.bodyToFlow

/**
 * The coroutine endpoints, which use the extensions of spring-chat-kotlin. Each test names its
 * conversation, so that the tests do not meet in the memory of one.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AskEndpointTests(@LocalServerPort port: Int) {

    private val client = WebClient.create("http://localhost:$port")

    @Test
    fun askReturnsTheWholeReplyFromASuspendFunction() = runBlocking<Unit> {
        val reply = get("/ask?text=hello there&conversation=complete-1")

        assertThat(reply).startsWith("You said: **hello there**")
            .contains("That was message 1 of this conversation")
    }

    @Test
    fun askStreamSendsOneEventForEachPieceOfTheReply() = runBlocking<Unit> {
        val pieces = within {
            client.get().uri("/ask/stream?text=hello there&conversation=stream-1")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve().bodyToFlow<String>().toList()
        }

        // The model writes a word at a time: the first pieces are its first words, in order.
        assertThat(pieces.take(4)).containsExactly("You ", "said: ", "**hello ", "there**\n\nThat ")
        assertThat(pieces.size).isGreaterThan(4)
        assertThat(pieces.joinToString("")).startsWith("You said: **hello there**")
    }

    @Test
    fun aConversationIsRemembered() = runBlocking<Unit> {
        get("/ask?text=one&conversation=memory-1")

        assertThat(get("/ask?text=two&conversation=memory-1")).contains("That was message 2 of this conversation")
        assertThat(get("/ask?text=two&conversation=memory-2")).contains("That was message 1 of this conversation")
    }

    @Test
    fun aRequestWithoutAConversationHasOneOfItsOwn() = runBlocking<Unit> {
        assertThat(get("/ask?text=one")).contains("message 1 of")
        assertThat(get("/ask?text=two")).contains("message 1 of")
    }

    @Test
    fun repliesDescribeEachKindOfReply() = runBlocking<Unit> {
        val reply = get("/ask/replies?text=hello&conversation=replies-1")

        // Only text, which the demo model writes: no note of a pause or of an early end.
        assertThat(reply).startsWith("You said: **hello**").doesNotContain("[")
    }

    private suspend fun get(path: String): String = within { client.get().uri(path).retrieve().awaitBody<String>() }

    /** With a limit, so that a reply that never ends fails its test and does not hold the build. */
    private suspend fun <T> within(block: suspend () -> T): T = withTimeout(30.seconds) { block() }
}

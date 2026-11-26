package io.github.hjosugi.springchat

import java.security.Principal
import kotlin.reflect.typeOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux

/**
 * What the Java API gives a Kotlin caller with no Kotlin code of ours. Each test is the
 * proof of one claim in the README's Kotlin section, and the extensions do not repeat
 * any of them: if one of these stops compiling, an extension is needed.
 */
class JavaApiFromKotlinTests {

    /**
     * An expression `when` has to be exhaustive, so this compiles only if Kotlin knows the
     * three subtypes of the sealed `ChatReply`: there is no `else`.
     */
    @Test
    fun whenOverTheSealedChatReplyNeedsNoElse() {
        fun describe(reply: ChatReply): String = when (reply) {
            is ChatReply.Text -> "text ${reply.delta}"
            is ChatReply.Confirmation -> "confirm ${reply.calls.map { it.name }}"
            is ChatReply.Ended -> "ended ${reply.reason}"
        }

        assertThat(describe(ChatReply.Text("hi"))).isEqualTo("text hi")
        assertThat(describe(ChatReply.Confirmation(listOf(ChatReply.PendingToolCall("1", "cancelOrder", "{}")))))
            .isEqualTo("confirm [cancelOrder]")
        assertThat(describe(ChatReply.Ended(ChatReply.Reason.TRUNCATED))).isEqualTo("ended TRUNCATED")
    }

    @Test
    fun whenOverTheReasonEnumNeedsNoElse() {
        fun describe(reason: ChatReply.Reason): String = when (reason) {
            ChatReply.Reason.TRUNCATED -> "too long"
            ChatReply.Reason.FILTERED -> "stopped"
        }

        assertThat(ChatReply.Reason.entries.map(::describe)).containsExactly("too long", "stopped")
    }

    @Test
    fun aChatPromptCustomizerIsALambda() {
        var seen: ChatRequest? = null
        val customizer = ChatPromptCustomizer { _, request -> seen = request }
        val request = ChatRequest("id", "text")

        customizer.customize(promptSpec(), request)

        assertThat(seen).isSameAs(request)
    }

    @Test
    fun aLambdaConvertsWhereAChatPromptCustomizerIsExpected() {
        var calls = 0

        // A Kotlin function that takes the Java interface: the trailing lambda converts, as a
        // bean method that returns a ChatPromptCustomizer does.
        fun use(customizer: ChatPromptCustomizer) = customizer.customize(promptSpec(), ChatRequest("id", "text"))
        use { _, _ -> calls++ }

        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun aChatServiceIsALambdaAndKeepsItsDefaultMethod() {
        val service = ChatService { Flux.just("a", "b") }

        assertThat(service.stream(ChatRequest("id", "text")).collectList().block()).containsExactly("a", "b")
        // reply is a default method of the interface, and the lambda has it.
        assertThat(service.reply(ChatRequest("id", "text")).collectList().block())
            .containsExactly(ChatReply.Text("a"), ChatReply.Text("b"))
    }

    @Test
    fun aLambdaConvertsWhereAChatServiceIsExpected() {
        fun firstPiece(service: ChatService) = service.stream(ChatRequest("id", "text")).blockFirst()

        assertThat(firstPiece { request -> Flux.just(request.text.uppercase()) }).isEqualTo("TEXT")
    }

    @Test
    fun theConvenienceConstructorsLeaveThePrincipalNull() {
        val request = ChatRequest("id", "text")

        assertThat(request.principal).isNull()
        assertThat(request.decisions).isEmpty()
        assertThat(ChatRequest("id", "text", null).principal).isNull()
        assertThat(ChatRequest("id", "text", Principal { "alice" }).principal?.name).isEqualTo("alice")
    }

    /**
     * The nullness is JSpecify's, which Kotlin 2.1 and later read as strict. The principal is
     * `Principal?` and the rest has no `!`: Kotlin does not treat a type as a platform type,
     * of which it knows nothing, but as the type that the annotations say. The first line is the
     * control: a Java type without annotations does show up as a platform type, so the lines
     * after it are not an accident of how Kotlin prints types.
     */
    @Test
    fun nullnessIsStrictAndNotAPlatformType() {
        val request = ChatRequest("id", "text")

        val unannotated = typeOf(System.getProperty("java.version"))
        assertThat(unannotated.toString()).contains("!")

        val principal = typeOf(request.principal)
        assertThat(principal.isMarkedNullable).isTrue()
        assertThat(principal.toString()).doesNotContain("!")
        assertThat(typeOf(request.text).isMarkedNullable).isFalse()
        assertThat(typeOf(request.text).toString()).doesNotContain("!")
        assertThat(typeOf(request.conversationId).toString()).doesNotContain("!")
        assertThat(typeOf(request.decisions).toString()).doesNotContain("!")
    }

    private inline fun <reified T> typeOf(@Suppress("UNUSED_PARAMETER") value: T) = typeOf<T>()

    private fun promptSpec(): ChatClient.ChatClientRequestSpec {
        val model = object : ChatModel {
            override fun call(prompt: Prompt): ChatResponse =
                ChatResponse(listOf(Generation(AssistantMessage("unused"))))
        }
        return ChatClient.create(model).prompt()
    }
}

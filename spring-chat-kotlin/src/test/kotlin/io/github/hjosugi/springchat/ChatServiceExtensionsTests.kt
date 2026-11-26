package io.github.hjosugi.springchat

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux

class ChatServiceExtensionsTests {

    private val request = ChatRequest("conversation", "hello")

    // streamFlow

    @Test
    fun streamFlowEmitsWhatTheServiceStreamsInOrder() = runBlocking<Unit> {
        val service = ChatService { Flux.just("one ", "two ", "three") }

        assertThat(service.streamFlow(request).toList()).containsExactly("one ", "two ", "three")
    }

    @Test
    fun streamFlowAsksTheServiceWithTheRequest() = runBlocking<Unit> {
        var asked: ChatRequest? = null
        val service = ChatService { asked = it; Flux.empty() }

        service.streamFlow(request).toList()

        assertThat(asked).isSameAs(request)
    }

    @Test
    fun streamFlowAsksTheServiceWhenCollectedAndOnceForEachCollection() = runBlocking<Unit> {
        val asked = AtomicInteger()
        val service = ChatService { asked.incrementAndGet(); Flux.just("a") }

        val flow = service.streamFlow(request)
        assertThat(asked).hasValue(0)

        flow.toList()
        flow.toList()
        assertThat(asked).hasValue(2)
    }

    @Test
    fun streamFlowEmitsNothingForAnEmptyReply() = runBlocking<Unit> {
        val service = ChatService { Flux.empty() }

        assertThat(service.streamFlow(request).toList()).isEmpty()
    }

    @Test
    fun streamFlowCancelsTheStreamWhenTheCollectorTakesWhatItWants() = runBlocking<Unit> {
        val cancelled = CompletableDeferred<Unit>()
        val service = ChatService { endless(cancelled) }

        assertThat(service.streamFlow(request).take(2).toList()).containsExactly("piece 0", "piece 1")

        withTimeout(10.seconds) { cancelled.await() }
    }

    @Test
    fun streamFlowCancelsTheStreamWhenTheCollectorIsCancelled() = runBlocking<Unit> {
        val cancelled = CompletableDeferred<Unit>()
        val receiving = CompletableDeferred<String>()
        val service = ChatService { endless(cancelled) }

        val collector = launch(Dispatchers.Default) {
            service.streamFlow(request).collect { receiving.complete(it) }
        }
        withTimeout(10.seconds) { receiving.await() }
        collector.cancel()

        withTimeout(10.seconds) { cancelled.await() }
        collector.join()
        assertThat(collector.isCancelled).isTrue()
    }

    @Test
    fun streamFlowPassesOnTheErrorAfterThePiecesBeforeIt() = runBlocking<Unit> {
        val service = ChatService { Flux.concat(Flux.just("a", "b"), Flux.error(IllegalStateException("boom"))) }
        val received = mutableListOf<String>()
        var failure: Throwable? = null

        service.streamFlow(request).catch { failure = it }.collect { received += it }

        assertThat(received).containsExactly("a", "b")
        // Coroutines may hand on a copy of the exception, with the original as its cause.
        assertThat(failure).isInstanceOf(IllegalStateException::class.java).hasMessage("boom")
    }

    @Test
    fun streamFlowPassesOnAnErrorThatTheServiceThrowsInsteadOfReturningAStream() = runBlocking<Unit> {
        val service = ChatService { throw IllegalArgumentException("no stream") }
        var failure: Throwable? = null

        service.streamFlow(request).catch { failure = it }.collect()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java).hasMessage("no stream")
    }

    // replyFlow

    @Test
    fun replyFlowEmitsEveryKindOfReplyInOrder() = runBlocking<Unit> {
        val replies = listOf(
            ChatReply.Text("Cancelling "),
            ChatReply.Text("the order. "),
            ChatReply.Confirmation(listOf(ChatReply.PendingToolCall("1", "cancelOrder", "{}"))),
            ChatReply.Ended(ChatReply.Reason.TRUNCATED),
        )
        val service = object : ChatService {
            override fun stream(request: ChatRequest): Flux<String> = Flux.empty()
            override fun reply(request: ChatRequest): Flux<ChatReply> = Flux.fromIterable(replies)
        }

        assertThat(service.replyFlow(request).toList()).containsExactlyElementsOf(replies)
    }

    @Test
    fun replyFlowOfAServiceThatOnlyStreamsTextEmitsTheTextAsReplies() = runBlocking<Unit> {
        val service = ChatService { Flux.just("a", "b") }

        assertThat(service.replyFlow(request).toList()).containsExactly(ChatReply.Text("a"), ChatReply.Text("b"))
    }

    @Test
    fun replyFlowAsksTheServiceWhenCollected() = runBlocking<Unit> {
        val asked = AtomicInteger()
        val service = object : ChatService {
            override fun stream(request: ChatRequest): Flux<String> = Flux.empty()
            override fun reply(request: ChatRequest): Flux<ChatReply> {
                asked.incrementAndGet()
                return Flux.empty()
            }
        }

        val flow = service.replyFlow(request)
        assertThat(asked).hasValue(0)

        flow.toList()
        assertThat(asked).hasValue(1)
    }

    @Test
    fun replyFlowCancelsTheStreamWhenTheCollectorTakesWhatItWants() = runBlocking<Unit> {
        val cancelled = CompletableDeferred<Unit>()
        val service = ChatService { endless(cancelled) }

        assertThat(service.replyFlow(request).first()).isEqualTo(ChatReply.Text("piece 0"))

        withTimeout(10.seconds) { cancelled.await() }
    }

    @Test
    fun replyFlowPassesOnTheErrorAfterTheRepliesBeforeIt() = runBlocking<Unit> {
        val service = ChatService { Flux.concat(Flux.just("a"), Flux.error(IllegalStateException("boom"))) }
        val received = mutableListOf<ChatReply>()
        var failure: Throwable? = null

        service.replyFlow(request).catch { failure = it }.collect { received += it }

        assertThat(received).containsExactly(ChatReply.Text("a"))
        // Coroutines may hand on a copy of the exception, with the original as its cause.
        assertThat(failure).isInstanceOf(IllegalStateException::class.java).hasMessage("boom")
    }

    // complete

    @Test
    fun completeJoinsTheTextOfTheReply() = runBlocking<Unit> {
        val service = ChatService { Flux.just("Hel", "lo, ", "world") }

        assertThat(service.complete(request)).isEqualTo("Hello, world")
    }

    @Test
    fun completeOfAnEmptyReplyIsAnEmptyString() = runBlocking<Unit> {
        val service = ChatService { Flux.empty() }

        assertThat(service.complete(request)).isEmpty()
    }

    @Test
    fun completeAsksTheServiceWithTheRequestWhenCalled() = runBlocking<Unit> {
        val asked = mutableListOf<ChatRequest>()
        val service = ChatService { asked += it; Flux.just("ok") }

        assertThat(asked).isEmpty()
        service.complete(request)

        assertThat(asked).containsExactly(request)
    }

    @Test
    fun completeHasTheTextOfAReplyThatStoppedForAConfirmationAndNothingElse() = runBlocking<Unit> {
        // As the default service does: stream is the text of reply.
        val service = object : ChatService {
            override fun stream(request: ChatRequest): Flux<String> =
                reply(request).ofType(ChatReply.Text::class.java).map { it.delta }

            override fun reply(request: ChatRequest): Flux<ChatReply> = Flux.just(
                ChatReply.Text("Cancelling the order. "),
                ChatReply.Confirmation(listOf(ChatReply.PendingToolCall("1", "cancelOrder", "{}"))),
            )
        }

        assertThat(service.complete(request)).isEqualTo("Cancelling the order. ")
    }

    @Test
    fun completeThrowsTheErrorOfTheService() {
        val service = ChatService { Flux.concat(Flux.just("a"), Flux.error(IllegalStateException("boom"))) }

        assertThatThrownBy { runBlocking { service.complete(request) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("boom")
    }

    @Test
    fun completeCancelsTheStreamWhenTheCallerIsCancelled() = runBlocking<Unit> {
        val cancelled = CompletableDeferred<Unit>()
        val begun = CompletableDeferred<Unit>()
        val service = ChatService { endless(cancelled).doOnNext { begun.complete(Unit) } }

        val caller = launch(Dispatchers.Default) { service.complete(request) }
        // The stream has begun when the caller is cancelled: a stream that is cancelled
        // before it begins would not show that a running one is.
        withTimeout(10.seconds) { begun.await() }
        caller.cancel()

        withTimeout(10.seconds) { cancelled.await() }
    }

    /**
     * A reply that never ends, and completes [cancelled] when its subscriber cancels it.
     */
    private fun endless(cancelled: CompletableDeferred<Unit>): Flux<String> =
        Flux.interval(Duration.ofMillis(5)).map { "piece $it" }.doOnCancel { cancelled.complete(Unit) }
}

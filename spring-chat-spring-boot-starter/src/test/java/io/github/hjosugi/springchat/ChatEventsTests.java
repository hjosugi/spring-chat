package io.github.hjosugi.springchat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ChatEventsTests {

	@Test
	void aReplyIsStartThenTextThenDone() {
		ChatEvents events = new ChatEvents((request) -> Flux.just("Hel", "", "lo"), 100);

		StepVerifier.create(events.stream(new ChatRequest("c1", "hi")))
			.expectNext(ChatEvent.start(), ChatEvent.text("Hel"), ChatEvent.text("lo"), ChatEvent.done())
			.verifyComplete();
	}

	@Test
	void aFailureEndsTheReplyWithAnErrorThatHidesTheCause() {
		ChatEvents events = new ChatEvents(
				(request) -> Flux.just("Hel").concatWith(Flux.error(new IllegalStateException("secret-key"))), 100);

		StepVerifier.create(events.stream(new ChatRequest("c1", "hi")))
			.expectNext(ChatEvent.start(), ChatEvent.text("Hel"))
			.expectNext(ChatEvent.error("The reply could not be completed."))
			.verifyComplete();
	}

	@Test
	void aServiceThatThrowsEndsTheReplyWithAnError() {
		ChatEvents events = new ChatEvents((request) -> {
			throw new IllegalStateException("secret-key");
		}, 100);

		StepVerifier.create(events.stream(new ChatRequest("c1", "hi")))
			.expectNext(ChatEvent.start(), ChatEvent.error("The reply could not be completed."))
			.verifyComplete();
	}

	@Test
	void aMessageWithoutTextOrConversationOrOverALimitIsRejectedBeforeTheServiceSeesIt() {
		ChatEvents events = new ChatEvents((request) -> Flux.error(new AssertionError("asked")), 5);

		for (ChatRequest request : new ChatRequest[] { new ChatRequest("c1", " "), new ChatRequest("c1", null),
				new ChatRequest(null, "hi"), new ChatRequest("c1", "123456"), new ChatRequest("c".repeat(101), "hi") }) {
			StepVerifier.create(events.stream(request))
				.expectNext(ChatEvent.start())
				.expectNextMatches((event) -> ChatEvent.ERROR.equals(event.type()) && event.message() != null)
				.verifyComplete();
		}
	}

	@Test
	void aQuietReplySendsPingsUntilItEnds() {
		ChatEvents events = new ChatEvents((request) -> Mono.delay(Duration.ofSeconds(12)).map((tick) -> "late").flux(),
				100);

		StepVerifier.withVirtualTime(() -> events.stream(new ChatRequest("c1", "hi")))
			.expectNext(ChatEvent.start())
			.expectNoEvent(ChatEvents.KEEP_ALIVE)
			.expectNext(ChatEvent.ping())
			.expectNoEvent(ChatEvents.KEEP_ALIVE)
			.expectNext(ChatEvent.ping())
			.thenAwait(Duration.ofSeconds(2))
			.expectNext(ChatEvent.text("late"), ChatEvent.done())
			.verifyComplete();
	}

}

package io.github.hjosugi.springchat.web;

import java.security.Principal;
import java.time.Duration;

import io.github.hjosugi.springchat.ChatRequest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class ChatEventsTests {

	private static final BrowserMessage HI = new BrowserMessage("message", "c1", "hi");

	@Test
	void aReplyIsStartThenTextThenDone() {
		ChatEvents events = new ChatEvents((request) -> Flux.just("Hel", "", "lo"), 100);

		StepVerifier.create(events.stream(HI, null))
			.expectNext(ChatEvent.start(), ChatEvent.text("Hel"), ChatEvent.text("lo"), ChatEvent.done())
			.verifyComplete();
	}

	@Test
	void theServiceGetsTheMessageAndWhoSentIt() {
		Principal alice = () -> "alice";
		ChatRequest[] received = new ChatRequest[1];
		ChatEvents events = new ChatEvents((request) -> {
			received[0] = request;
			return Flux.empty();
		}, 100);

		StepVerifier.create(events.stream(HI, alice)).expectNext(ChatEvent.start(), ChatEvent.done()).verifyComplete();
		assertThat(received[0]).isEqualTo(new ChatRequest("c1", "hi", alice));
	}

	@Test
	void aFailureEndsTheReplyWithAnErrorThatHidesTheCause() {
		ChatEvents events = new ChatEvents(
				(request) -> Flux.just("Hel").concatWith(Flux.error(new IllegalStateException("secret-key"))), 100);

		StepVerifier.create(events.stream(HI, null))
			.expectNext(ChatEvent.start(), ChatEvent.text("Hel"))
			.expectNext(ChatEvent.error("The reply could not be completed."))
			.verifyComplete();
	}

	@Test
	void aServiceThatThrowsEndsTheReplyWithAnError() {
		ChatEvents events = new ChatEvents((request) -> {
			throw new IllegalStateException("secret-key");
		}, 100);

		StepVerifier.create(events.stream(HI, null))
			.expectNext(ChatEvent.start(), ChatEvent.error("The reply could not be completed."))
			.verifyComplete();
	}

	@Test
	void aMessageWithoutTextOrConversationOrOverALimitIsRejectedBeforeTheServiceSeesIt() {
		ChatEvents events = new ChatEvents((request) -> Flux.error(new AssertionError("asked")), 5);

		for (BrowserMessage message : new BrowserMessage[] { new BrowserMessage(null, "c1", " "),
				new BrowserMessage(null, "c1", null), new BrowserMessage(null, null, "hi"), BrowserMessage.EMPTY,
				new BrowserMessage(null, "c1", "123456"), new BrowserMessage(null, "c".repeat(101), "hi") }) {
			StepVerifier.create(events.stream(message, null))
				.expectNext(ChatEvent.start())
				.expectNextMatches((event) -> ChatEvent.ERROR.equals(event.type()) && event.message() != null)
				.verifyComplete();
		}
	}

	@Test
	void aQuietReplySendsPingsUntilItEnds() {
		ChatEvents events = new ChatEvents((request) -> Mono.delay(Duration.ofSeconds(12)).map((tick) -> "late").flux(),
				100);

		StepVerifier.withVirtualTime(() -> events.stream(HI, null))
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

package io.github.hjosugi.springchat;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
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
	void aMessageWithoutTextOrConversationOrOverTheLimitIsRejected() {
		ChatEvents events = new ChatEvents((request) -> Flux.just("never"), 5);

		for (ChatRequest request : new ChatRequest[] { null, new ChatRequest("c1", " "), new ChatRequest(null, "hi"),
				new ChatRequest("c1", "123456") }) {
			StepVerifier.create(events.stream(request))
				.expectNextMatches((event) -> ChatEvent.ERROR.equals(event.type()))
				.verifyComplete();
		}
	}

}

package io.github.hjosugi.springchat.web;

import java.security.Principal;
import java.time.Duration;
import java.util.List;

import io.github.hjosugi.springchat.AttachmentProperties;
import io.github.hjosugi.springchat.ChatReply;
import io.github.hjosugi.springchat.ChatRequest;
import io.github.hjosugi.springchat.ToolDecision;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.util.MimeTypeUtils;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

class ChatEventsTests {

	private static final BrowserMessage HI = new BrowserMessage("message", "c1", "hi");

	@Test
	void invalidAttachmentsNeverReachTheService() {
		var limits = new AttachmentProperties(true, 1, DataSize.ofBytes(2), List.of(MimeTypeUtils.IMAGE_PNG));
		var events = new ChatEvents((req) -> Flux.error(new AssertionError("asked")), 100, limits);
		var file = new BrowserMessage.Attachment("photo.png", "image/png", "AQI=");
		for (var files : List.of(List.of(file, file), List.of(new BrowserMessage.Attachment(null, "image/png", "AQ==")),
				List.of(new BrowserMessage.Attachment("x", "image/png", "AQID")),
				List.of(new BrowserMessage.Attachment("x", "image/png", "!")),
				List.of(new BrowserMessage.Attachment("x", "image/*", "AQ==")),
				List.of(new BrowserMessage.Attachment("x", "image/jpeg", "AQ==")))) {
			rejects(events, new BrowserMessage(null, "c", "caption", null, files));
		}
		rejects(events, new BrowserMessage(null, "c", null, List.of(new BrowserMessage.Decision("1", true)), List.of(file)));
		var disabled = new ChatEvents((req) -> Flux.error(new AssertionError("asked")), 100);
		rejects(disabled, new BrowserMessage(null, "c", "caption", null, List.of(file)));
	}

	private static void rejects(ChatEvents events, BrowserMessage message) {
		StepVerifier.create(events.stream(message, null)).expectNext(ChatEvent.start())
			.expectNextMatches((event) -> event.type().equals(ChatEvent.ERROR))
			.verifyComplete();
	}

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
	void aReplyThatWaitsForADecisionEndsWithTheCallsAndNotWithDone() {
		ChatEvents pausing = new ChatEvents(new io.github.hjosugi.springchat.ChatService() {

			@Override
			public Flux<String> stream(ChatRequest request) {
				return Flux.empty();
			}

			@Override
			public Flux<ChatReply> reply(ChatRequest request) {
				return Flux.just(new ChatReply.Text("asking"), new ChatReply.Confirmation(
						List.of(new ChatReply.PendingToolCall("call_1", "cancelOrder", "{}"))));
			}

		}, 100);

		StepVerifier.create(pausing.stream(HI, null))
			.expectNext(ChatEvent.start(), ChatEvent.text("asking"))
			.expectNext(ChatEvent.confirm(List.of(new ChatEvent.Call("call_1", "cancelOrder", "{}"))))
			.verifyComplete();
	}

	@Test
	void aReplyThatEndedBeforeItsEndHasOnlyThatOneDone() {
		ChatEvents events = new ChatEvents(new io.github.hjosugi.springchat.ChatService() {

			@Override
			public Flux<String> stream(ChatRequest request) {
				return Flux.empty();
			}

			@Override
			public Flux<ChatReply> reply(ChatRequest request) {
				return Flux.just(new ChatReply.Text("long"), new ChatReply.Ended(ChatReply.Reason.FILTERED));
			}

		}, 100);

		StepVerifier.create(events.stream(HI, null))
			.expectNext(ChatEvent.start(), ChatEvent.text("long"), ChatEvent.done("filtered"))
			.verifyComplete();
	}

	@Test
	void decisionsMakeARequestWithoutText() {
		ChatRequest[] received = new ChatRequest[1];
		ChatEvents events = new ChatEvents((request) -> {
			received[0] = request;
			return Flux.empty();
		}, 100);
		BrowserMessage message = new BrowserMessage("message", "c1", null,
				List.of(new BrowserMessage.Decision("call_1", true), new BrowserMessage.Decision("call_2", false)));

		StepVerifier.create(events.stream(message, () -> "alice")).expectNext(ChatEvent.start(), ChatEvent.done()).verifyComplete();

		assertThat(received[0].text()).isEmpty();
		assertThat(received[0].decisions()).containsExactly(new ToolDecision("call_1", true),
				new ToolDecision("call_2", false));
	}

	@Test
	void aMessageWithBothATextAndDecisionsIsRejected() {
		ChatEvents events = new ChatEvents((request) -> Flux.error(new AssertionError("asked")), 100);

		StepVerifier.create(events.stream(new BrowserMessage("message", "c1", "hi",
				List.of(new BrowserMessage.Decision("call_1", true))), null))
			.expectNext(ChatEvent.start())
			.expectNextMatches((event) -> ChatEvent.ERROR.equals(event.type()))
			.verifyComplete();
	}

	@Test
	void theServiceIsAskedOnAWorkerThreadAndNotOnTheCallers() {
		String[] thread = new String[1];
		ChatEvents events = new ChatEvents((request) -> Flux.defer(() -> {
			thread[0] = Thread.currentThread().getName();
			return Flux.just("hi");
		}), 100);

		StepVerifier.create(events.stream(HI, null))
			.expectNext(ChatEvent.start(), ChatEvent.text("hi"), ChatEvent.done())
			.verifyComplete();
		// Reactor names its threads loomBoundedElastic- when they are virtual.
		assertThat(thread[0]).matches("(loomB|b)oundedElastic-\\d+").isNotEqualTo(Thread.currentThread().getName());
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

package io.github.hjosugi.springchat.web;

import io.github.hjosugi.springchat.ChatEvent;
import io.github.hjosugi.springchat.ChatEvents;
import io.github.hjosugi.springchat.ChatRequest;
import reactor.core.publisher.Flux;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SSE transport: a POST with the message, answered by the reply's events.
 * Spring MVC and WebFlux both stream a {@link Flux} of {@link ServerSentEvent}s.
 */
@RestController
public class ChatStreamController {

	private final ChatEvents events;

	public ChatStreamController(ChatEvents events) {
		this.events = events;
	}

	@PostMapping(path = "${spring-chat.path:/chat}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<ChatEvent>> stream(@RequestBody ChatRequest request) {
		return this.events.stream(request)
			.map((event) -> ServerSentEvent.builder(event).event(event.type()).build());
	}

}

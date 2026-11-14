package io.github.hjosugi.springchat.web;

import java.security.Principal;

import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SSE transport, below {@code spring-chat.path}: a POST with the message,
 * answered by the reply's events. Spring MVC and WebFlux both stream a {@link Flux}
 * of {@link ServerSentEvent}s, and both resolve the {@link Principal}.
 */
@RestController
public class ChatStreamController {

	private final ChatEvents events;

	public ChatStreamController(ChatEvents events) {
		this.events = events;
	}

	@PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseEntity<Flux<ServerSentEvent<String>>> stream(@RequestBody String message,
			@Nullable Principal principal) {
		Flux<ServerSentEvent<String>> events = this.events.stream(ChatJson.read(message), principal)
			.map((event) -> ServerSentEvent.builder(ChatJson.write(event)).event(event.type()).build());
		// A proxy must pass each event on as it comes: nginx buffers a response unless
		// told not to, and one that compresses holds text back.
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noCache().noTransform())
			.header("X-Accel-Buffering", "no")
			.body(events);
	}

}

package io.github.hjosugi.springchat.web;

import java.security.Principal;

import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;

/**
 * The SSE transport, below {@code spring-chat.path}: a POST with the message,
 * answered by the reply's events. Spring MVC and WebFlux both stream a {@link Flux}
 * of {@link ServerSentEvent}s, and both resolve the {@link Principal}.
 */
public abstract class ChatStreamController {

	private final ChatEvents events;

	protected final ChatJson json;

	protected ChatStreamController(ChatEvents events, ChatJson json) {
		this.events = events;
		this.json = json;
	}

	protected ResponseEntity<Flux<ServerSentEvent<String>>> reply(String message, @Nullable Principal principal) {
		Flux<ServerSentEvent<String>> events = this.events.stream(this.json.read(message), principal)
			.map((event) -> ServerSentEvent.builder(this.json.write(event)).event(event.type()).build());
		// A proxy must pass each event on as it comes: nginx buffers a response unless
		// told not to, and one that compresses holds text back.
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noCache().noTransform())
			.header("X-Accel-Buffering", "no")
			.body(events);
	}

}

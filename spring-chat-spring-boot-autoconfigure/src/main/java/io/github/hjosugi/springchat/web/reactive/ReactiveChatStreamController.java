package io.github.hjosugi.springchat.web.reactive;

import java.nio.charset.StandardCharsets;
import java.security.Principal;

import io.github.hjosugi.springchat.web.ChatEvents;
import io.github.hjosugi.springchat.web.ChatJson;
import io.github.hjosugi.springchat.web.ChatStreamController;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ReactiveChatStreamController extends ChatStreamController {

	public ReactiveChatStreamController(ChatEvents events, ChatJson json) {
		super(events, json);
	}

	@PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Mono<ResponseEntity<Flux<ServerSentEvent<String>>>> stream(ServerHttpRequest request,
			@Nullable Principal principal) {
		return DataBufferUtils.join(request.getBody(), this.json.maxRequestBytes())
			.map((buffer) -> {
				try {
					return buffer.toString(StandardCharsets.UTF_8);
				}
				finally {
					DataBufferUtils.release(buffer);
				}
			})
			.onErrorMap(DataBufferLimitException.class, (ex) -> new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE))
			.defaultIfEmpty("")
			.map((message) -> reply(message, principal));
	}

}

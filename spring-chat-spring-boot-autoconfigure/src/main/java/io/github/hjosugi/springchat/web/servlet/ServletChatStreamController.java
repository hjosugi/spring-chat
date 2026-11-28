package io.github.hjosugi.springchat.web.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.Principal;

import io.github.hjosugi.springchat.web.ChatEvents;
import io.github.hjosugi.springchat.web.ChatJson;
import io.github.hjosugi.springchat.web.ChatStreamController;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ServletChatStreamController extends ChatStreamController {

	public ServletChatStreamController(ChatEvents events, ChatJson json) {
		super(events, json);
	}

	@PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseEntity<Flux<ServerSentEvent<String>>> stream(InputStream input, @Nullable Principal principal)
			throws IOException {
		byte[] body = input.readNBytes(this.json.maxRequestBytes() + 1);
		if (body.length > this.json.maxRequestBytes()) {
			throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
		}
		return reply(new String(body, StandardCharsets.UTF_8), principal);
	}

}

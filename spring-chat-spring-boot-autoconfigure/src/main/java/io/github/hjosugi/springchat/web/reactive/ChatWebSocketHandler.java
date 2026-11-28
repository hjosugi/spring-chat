package io.github.hjosugi.springchat.web.reactive;

import java.security.Principal;
import java.util.Optional;

import io.github.hjosugi.springchat.web.ChatEvents;
import io.github.hjosugi.springchat.web.ChatJson;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

/**
 * The WebSocket transport on WebFlux. A connection has one reply at a time:
 * a new message or a stop ends the running one.
 */
public class ChatWebSocketHandler implements WebSocketHandler {

	private final ChatEvents events;

	private final ChatJson json;

	public ChatWebSocketHandler(ChatEvents events, ChatJson json) {
		this.events = events;
		this.json = json;
	}

	@Override
	public Mono<Void> handle(WebSocketSession session) {
		// The principal is the one of the handshake, which went through the security filters.
		return session.getHandshakeInfo()
			.getPrincipal()
			.map(Optional::of)
			.defaultIfEmpty(Optional.empty())
			.flatMap((principal) -> session.send(replies(session, principal)));
	}

	private Flux<WebSocketMessage> replies(WebSocketSession session, Optional<Principal> principal) {
		return session.receive()
			// A pong, say, is not a message and must not end the running reply.
			.filter((frame) -> frame.getType() == WebSocketMessage.Type.TEXT)
			.map((frame) -> this.json.read(frame.getPayloadAsText()))
			.switchMap((message) -> message.isStop() ? Flux.empty()
					: this.events.stream(message, principal.orElse(null)))
			.map((event) -> session.textMessage(this.json.write(event)));
	}

}

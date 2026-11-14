package io.github.hjosugi.springchat.web.reactive;

import io.github.hjosugi.springchat.ChatEvent;
import io.github.hjosugi.springchat.ChatEvents;
import io.github.hjosugi.springchat.web.ChatSocketMessage;
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

	public ChatWebSocketHandler(ChatEvents events) {
		this.events = events;
	}

	@Override
	public Mono<Void> handle(WebSocketSession session) {
		Flux<WebSocketMessage> replies = session.receive()
			.map(WebSocketMessage::getPayloadAsText)
			.map(ChatSocketMessage::parse)
			.switchMap(this::reply)
			.map(ChatSocketMessage::write)
			.map(session::textMessage);
		return session.send(replies);
	}

	private Flux<ChatEvent> reply(ChatSocketMessage message) {
		return message.isStop() ? Flux.empty() : this.events.stream(message.toRequest());
	}

}

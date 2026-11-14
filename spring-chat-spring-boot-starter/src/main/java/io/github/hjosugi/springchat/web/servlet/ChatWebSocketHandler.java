package io.github.hjosugi.springchat.web.servlet;

import java.io.IOException;

import io.github.hjosugi.springchat.ChatEvent;
import io.github.hjosugi.springchat.ChatEvents;
import io.github.hjosugi.springchat.web.ChatSocketMessage;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The WebSocket transport on Spring MVC. A connection has one reply at a time:
 * a new message or a stop ends the running one.
 */
public class ChatWebSocketHandler extends TextWebSocketHandler {

	private static final String REPLY = ChatWebSocketHandler.class.getName() + ".reply";

	private final ChatEvents events;

	public ChatWebSocketHandler(ChatEvents events) {
		this.events = events;
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage frame) {
		ChatSocketMessage message = ChatSocketMessage.parse(frame.getPayload());
		stopReply(session);
		if (message.isStop()) {
			return;
		}
		// Sending blocks, so it happens off the thread the model client emits on.
		Disposable reply = this.events.stream(message.toRequest())
			.publishOn(Schedulers.boundedElastic())
			.subscribe((event) -> send(session, event));
		session.getAttributes().put(REPLY, reply);
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		stopReply(session);
	}

	private void stopReply(WebSocketSession session) {
		if (session.getAttributes().remove(REPLY) instanceof Disposable reply) {
			reply.dispose();
		}
	}

	private void send(WebSocketSession session, ChatEvent event) {
		// A stopped reply may still be sending its last event when the next one starts.
		synchronized (session) {
			try {
				if (session.isOpen()) {
					session.sendMessage(new TextMessage(ChatSocketMessage.write(event)));
				}
			}
			catch (IOException ex) {
				stopReply(session);
			}
		}
	}

}

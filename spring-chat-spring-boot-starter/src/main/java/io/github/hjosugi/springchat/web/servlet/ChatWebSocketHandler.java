package io.github.hjosugi.springchat.web.servlet;

import java.io.IOException;

import io.github.hjosugi.springchat.ChatEvent;
import io.github.hjosugi.springchat.ChatEvents;
import io.github.hjosugi.springchat.web.BrowserMessage;
import io.github.hjosugi.springchat.web.ChatJson;
import reactor.core.publisher.Sinks;
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

	/**
	 * The session attribute that holds the running reply: the sink that stops it.
	 */
	private static final String REPLY = ChatWebSocketHandler.class.getName() + ".reply";

	private final ChatEvents events;

	private final int maxFrameLength;

	/**
	 * @param maxMessageLength the longest message, in characters, that must fit a frame
	 */
	public ChatWebSocketHandler(ChatEvents events, int maxMessageLength) {
		this.events = events;
		// As JSON a message is longer than its text: quotes and line breaks take two characters.
		this.maxFrameLength = 2 * maxMessageLength + 1024;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		// The server's own limit, 8192 on Tomcat, is less than the default longest message.
		session.setTextMessageSizeLimit(this.maxFrameLength);
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage frame) {
		BrowserMessage message = ChatJson.read(frame.getPayload());
		stopReply(session);
		if (message.isStop()) {
			return;
		}
		Sinks.Empty<Void> stop = Sinks.empty();
		session.getAttributes().put(REPLY, stop);
		// Stopped through the sink and not by disposing the subscription, which would
		// interrupt a send in progress, and Tomcat closes a connection it is interrupted on.
		this.events.stream(message.toRequest())
			.takeUntilOther(stop.asMono())
			// Sending blocks, so it happens off the thread the model client emits on.
			.publishOn(Schedulers.boundedElastic())
			.subscribe((event) -> send(session, stop, event));
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		stopReply(session);
	}

	private void stopReply(WebSocketSession session) {
		if (session.getAttributes().remove(REPLY) instanceof Sinks.Empty<?> stop) {
			stop.tryEmitEmpty();
		}
	}

	private void send(WebSocketSession session, Sinks.Empty<Void> reply, ChatEvent event) {
		// One send at a time, and none for a reply that is no longer the running one:
		// its last events may still be on their way here when the next reply starts.
		synchronized (session) {
			if (session.getAttributes().get(REPLY) != reply || !session.isOpen()) {
				return;
			}
			try {
				session.sendMessage(new TextMessage(ChatJson.write(event)));
			}
			catch (IOException | IllegalStateException ex) {
				// The browser has gone.
				stopReply(session);
			}
		}
	}

}

package io.github.hjosugi.springchat.web;

import io.github.hjosugi.springchat.ChatEvent;
import io.github.hjosugi.springchat.ChatRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the browser sends over the WebSocket: {@code message} starts a reply and
 * {@code stop} ends the running one. The server answers with {@link ChatEvent}s.
 *
 * @param type {@code message} or {@code stop}
 * @param conversationId see {@link ChatRequest}
 * @param text see {@link ChatRequest}
 */
public record ChatSocketMessage(String type, String conversationId, String text) {

	private static final JsonMapper json = JsonMapper.builder()
		.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.build();

	public boolean isStop() {
		return "stop".equals(this.type);
	}

	public ChatRequest toRequest() {
		return new ChatRequest(this.conversationId, this.text);
	}

	/**
	 * Reads a frame. An unreadable one becomes a message that {@code ChatEvents} rejects.
	 */
	public static ChatSocketMessage parse(String payload) {
		try {
			ChatSocketMessage message = json.readValue(payload, ChatSocketMessage.class);
			return (message != null) ? message : new ChatSocketMessage(null, null, null);
		}
		catch (JacksonException ex) {
			return new ChatSocketMessage(null, null, null);
		}
	}

	public static String write(ChatEvent event) {
		return json.writeValueAsString(event);
	}

}

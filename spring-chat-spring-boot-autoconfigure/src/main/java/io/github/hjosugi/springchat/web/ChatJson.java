package io.github.hjosugi.springchat.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON that the browser and the chat exchange, the same over both transports.
 * It has a mapper of its own, so the application's Jackson settings do not change it.
 */
public final class ChatJson {

	private static final JsonMapper mapper = JsonMapper.builder()
		.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.changeDefaultPropertyInclusion((inclusion) -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
		.build();

	private ChatJson() {
	}

	/**
	 * Reads what the browser sent. Anything unreadable becomes an empty message,
	 * which {@link ChatEvents} rejects.
	 */
	public static BrowserMessage read(String json) {
		try {
			BrowserMessage message = mapper.readValue(json, BrowserMessage.class);
			return (message != null) ? message : BrowserMessage.EMPTY;
		}
		catch (JacksonException ex) {
			return BrowserMessage.EMPTY;
		}
	}

	public static String write(ChatEvent event) {
		return mapper.writeValueAsString(event);
	}

}

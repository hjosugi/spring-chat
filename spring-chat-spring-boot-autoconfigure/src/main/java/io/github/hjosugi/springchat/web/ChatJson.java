package io.github.hjosugi.springchat.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON that the browser and the chat exchange, the same over both transports.
 * It has a mapper of its own, so the application's Jackson settings do not change it.
 */
public final class ChatJson {

	private final JsonMapper mapper;

	private final int maxRequestBytes;

	public ChatJson(int maxRequestBytes) {
		this.maxRequestBytes = maxRequestBytes;
		// Large enabled files may exceed Jackson's default maximum string length.
		// Bound both the encoded string and the whole document to the chat's wire limit.
		var constraints = StreamReadConstraints.builder().maxStringLength(maxRequestBytes)
			.maxDocumentLength(maxRequestBytes).build();
		this.mapper = JsonMapper.builder(JsonFactory.builder().streamReadConstraints(constraints).build())
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.changeDefaultPropertyInclusion((inclusion) -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
			.build();
	}

	public int maxRequestBytes() {
		return this.maxRequestBytes;
	}

	/**
	 * Reads what the browser sent. Anything unreadable becomes an empty message,
	 * which {@link ChatEvents} rejects.
	 */
	public BrowserMessage read(String json) {
		try {
			BrowserMessage message = this.mapper.readValue(json, BrowserMessage.class);
			return (message != null) ? message : BrowserMessage.EMPTY;
		}
		catch (JacksonException ex) {
			return BrowserMessage.EMPTY;
		}
	}

	public String write(ChatEvent event) {
		return this.mapper.writeValueAsString(event);
	}

}

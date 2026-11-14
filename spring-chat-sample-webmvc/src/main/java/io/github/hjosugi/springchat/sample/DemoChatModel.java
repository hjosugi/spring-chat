package io.github.hjosugi.springchat.sample;

import java.time.Duration;
import java.util.List;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stands in for a real model so the sample runs without an API key: it repeats the
 * message, word by word, and counts the conversation's messages to show the memory.
 */
@Component
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "demo")
class DemoChatModel implements ChatModel {

	@Override
	public ChatResponse call(Prompt prompt) {
		return response(reply(prompt));
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		return Flux.fromArray(reply(prompt).split("(?<= )")).delayElements(Duration.ofMillis(40)).map(this::response);
	}

	private String reply(Prompt prompt) {
		long messages = prompt.getInstructions()
			.stream()
			.filter((message) -> message.getMessageType() == MessageType.USER)
			.count();
		return "You said: **%s**\n\nThat was message %d of this conversation. Set `ANTHROPIC_API_KEY` for real answers."
			.formatted(prompt.getUserMessage().getText(), messages);
	}

	private ChatResponse response(String text) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
	}

}

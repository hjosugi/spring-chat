package io.github.hjosugi.springchat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Text written by the chat page. Placeholders can be moved to suit the language. */
@ConfigurationProperties("spring-chat.messages")
public record ChatMessages(
		@DefaultValue("The connection was lost before the reply finished.") String interrupted,
		@DefaultValue("The chat server could not be reached.") String unreachable,
		@DefaultValue("The chat server refused the message (HTTP {status}).") String refused,
		@DefaultValue("The reply was cut: it reached the longest length that the model may write.") String truncated,
		@DefaultValue("The model stopped the reply.") String filtered,
		@DefaultValue("The model gave no answer.") String empty,
		@DefaultValue("The assistant wants to run:") String confirm,
		@DefaultValue("Allow") String allow,
		@DefaultValue("Do not allow") String deny,
		@DefaultValue("Calling {name}…") String toolCall,
		@DefaultValue("The selected attachment could not be sent.") String attachmentError) {
}

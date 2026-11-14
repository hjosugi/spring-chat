package io.github.hjosugi.springchat.autoconfigure;

import io.github.hjosugi.springchat.ChatClientChatService;
import io.github.hjosugi.springchat.ChatProperties;
import io.github.hjosugi.springchat.ChatService;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

/**
 * {@link AutoConfiguration Auto-configuration} for the {@link ChatService}: unless the
 * application declares one, it asks the {@link ChatClient} that Spring AI configures.
 */
@AutoConfiguration(afterName = { "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration",
		"org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration" })
@ConditionalOnClass(ChatClient.class)
@ConditionalOnBooleanProperty(name = "spring-chat.enabled", matchIfMissing = true)
@EnableConfigurationProperties(ChatProperties.class)
public final class SpringChatAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean(ChatService.class)
	@ConditionalOnBean(ChatClient.Builder.class)
	ChatClientChatService chatService(ChatClient.Builder builder, ObjectProvider<ChatMemory> chatMemory,
			ChatProperties properties) {
		if (StringUtils.hasText(properties.systemPrompt())) {
			builder.defaultSystem(properties.systemPrompt());
		}
		if (properties.memory().enabled()) {
			chatMemory.ifAvailable((memory) -> builder.defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build()));
		}
		return new ChatClientChatService(builder.build());
	}

}

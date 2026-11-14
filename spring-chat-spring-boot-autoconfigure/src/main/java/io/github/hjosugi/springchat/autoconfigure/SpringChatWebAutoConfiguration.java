package io.github.hjosugi.springchat.autoconfigure;

import io.github.hjosugi.springchat.ChatProperties;
import io.github.hjosugi.springchat.ChatService;
import io.github.hjosugi.springchat.web.ChatEvents;
import io.github.hjosugi.springchat.web.ChatPageController;
import io.github.hjosugi.springchat.web.ChatStreamController;
import io.github.hjosugi.springchat.web.ChatView;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * {@link AutoConfiguration Auto-configuration} for the chat's web side, on Spring MVC
 * or WebFlux: the SSE and WebSocket endpoints and, with Thymeleaf, the built-in page and
 * the bean the fragment reads.
 */
@AutoConfiguration(after = SpringChatAutoConfiguration.class)
@ConditionalOnWebApplication
@ConditionalOnBean(ChatService.class)
@Import({ ServletChatConfiguration.class, ReactiveChatConfiguration.class })
@ImportRuntimeHints(ChatRuntimeHints.class)
public final class SpringChatWebAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	ChatEvents chatEvents(ChatService chatService, ChatProperties properties) {
		return new ChatEvents(chatService, properties.maxMessageLength());
	}

	@Bean
	@ConditionalOnMissingBean
	ChatStreamController chatStreamController(ChatEvents events) {
		return new ChatStreamController(events);
	}

	/**
	 * Whether a controller is one of the chat's, to be mapped below spring-chat.path.
	 * The path comes from the bound properties and not from a placeholder in the
	 * mappings, so every way to set the property moves all of the chat together.
	 */
	static boolean isChatController(Class<?> type) {
		return ChatStreamController.class.isAssignableFrom(type) || ChatPageController.class.isAssignableFrom(type);
	}

	/**
	 * The UI. Without Thymeleaf the endpoints serve a UI of the application's own.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(name = "org.thymeleaf.spring6.SpringTemplateEngine")
	static class TemplateConfiguration {

		/**
		 * Templates read this bean by its name, as {@code ${@springChat.transport}}.
		 */
		@Bean
		ChatView springChat(ChatProperties properties) {
			return ChatView.of(properties);
		}

		@Bean
		@ConditionalOnMissingBean
		@ConditionalOnBooleanProperty(name = "spring-chat.page.enabled", matchIfMissing = true)
		ChatPageController chatPageController() {
			return new ChatPageController();
		}

	}

}

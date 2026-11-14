package io.github.hjosugi.springchat.autoconfigure;

import java.util.Map;

import io.github.hjosugi.springchat.ChatEvents;
import io.github.hjosugi.springchat.ChatProperties;
import io.github.hjosugi.springchat.ChatService;
import io.github.hjosugi.springchat.ChatTransport;
import io.github.hjosugi.springchat.web.ChatPageController;
import io.github.hjosugi.springchat.web.ChatStreamController;
import io.github.hjosugi.springchat.web.ChatView;
import io.github.hjosugi.springchat.web.ChatViewAdvice;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;

/**
 * {@link AutoConfiguration Auto-configuration} for the chat's web side, on Spring MVC
 * or WebFlux: the SSE endpoint, the WebSocket endpoint, the built-in page and the
 * model attribute the Thymeleaf fragment reads.
 */
@AutoConfiguration(after = SpringChatAutoConfiguration.class)
@ConditionalOnWebApplication
@ConditionalOnBean(ChatService.class)
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

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnBooleanProperty(name = "spring-chat.page.enabled", matchIfMissing = true)
	ChatPageController chatPageController() {
		return new ChatPageController();
	}

	@Bean
	@ConditionalOnMissingBean
	ChatViewAdvice chatViewAdvice(ChatProperties properties) {
		return new ChatViewAdvice(ChatView.of(properties));
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnWebApplication(type = Type.SERVLET)
	@ConditionalOnClass(WebSocketConfigurer.class)
	@EnableWebSocket
	static class ServletWebSocketConfiguration {

		@Bean
		@ConditionalOnMissingBean
		io.github.hjosugi.springchat.web.servlet.ChatWebSocketHandler chatWebSocketHandler(ChatEvents events) {
			return new io.github.hjosugi.springchat.web.servlet.ChatWebSocketHandler(events);
		}

		@Bean
		WebSocketConfigurer chatWebSocketConfigurer(
				io.github.hjosugi.springchat.web.servlet.ChatWebSocketHandler handler, ChatProperties properties) {
			return (registry) -> registry.addHandler(handler, properties.socketPath());
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnWebApplication(type = Type.SERVLET)
	@ConditionalOnMissingClass("org.springframework.web.socket.config.annotation.WebSocketConfigurer")
	static class MissingServletWebSocketConfiguration {

		MissingServletWebSocketConfiguration(ChatProperties properties) {
			if (properties.transport() == ChatTransport.WEBSOCKET) {
				throw new IllegalStateException(
						"spring-chat.transport=websocket needs spring-boot-starter-websocket on the classpath");
			}
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnWebApplication(type = Type.REACTIVE)
	static class ReactiveWebSocketConfiguration {

		@Bean
		@ConditionalOnMissingBean
		io.github.hjosugi.springchat.web.reactive.ChatWebSocketHandler chatWebSocketHandler(ChatEvents events) {
			return new io.github.hjosugi.springchat.web.reactive.ChatWebSocketHandler(events);
		}

		@Bean
		HandlerMapping chatWebSocketHandlerMapping(
				io.github.hjosugi.springchat.web.reactive.ChatWebSocketHandler handler, ChatProperties properties) {
			// Ahead of the annotated controllers, as the WebFlux reference has it.
			return new SimpleUrlHandlerMapping(Map.of(properties.socketPath(), handler), -1);
		}

	}

}

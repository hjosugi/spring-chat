package io.github.hjosugi.springchat.autoconfigure;

import java.util.Map;

import io.github.hjosugi.springchat.ChatEvents;
import io.github.hjosugi.springchat.ChatProperties;
import io.github.hjosugi.springchat.ChatService;
import io.github.hjosugi.springchat.ChatTransport;
import io.github.hjosugi.springchat.web.ChatPageController;
import io.github.hjosugi.springchat.web.ChatStreamController;
import io.github.hjosugi.springchat.web.ChatView;

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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;

/**
 * {@link AutoConfiguration Auto-configuration} for the chat's web side, on Spring MVC
 * or WebFlux: the SSE endpoint, the WebSocket endpoint, the built-in page and the
 * bean the Thymeleaf fragment reads.
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

	/**
	 * Templates read this bean by its name, as {@code ${@springChat.transport}}.
	 */
	@Bean
	ChatView springChat(ChatProperties properties) {
		return ChatView.of(properties);
	}

	/**
	 * Whether a controller is one of the chat's, to be mapped below spring-chat.path.
	 * The path comes from the bound properties and not from a placeholder in the
	 * mappings, so every way to set the property moves all of the chat together.
	 */
	private static boolean isChatController(Class<?> type) {
		return ChatStreamController.class.isAssignableFrom(type) || ChatPageController.class.isAssignableFrom(type);
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnWebApplication(type = Type.SERVLET)
	static class ServletConfiguration {

		@Bean
		WebMvcConfigurer chatWebMvcConfigurer(ChatProperties properties) {
			return new WebMvcConfigurer() {

				@Override
				public void configurePathMatch(
						org.springframework.web.servlet.config.annotation.PathMatchConfigurer configurer) {
					configurer.addPathPrefix(properties.path(), SpringChatWebAutoConfiguration::isChatController);
				}

			};
		}

		@Configuration(proxyBeanMethods = false)
		@ConditionalOnClass(WebSocketConfigurer.class)
		@EnableWebSocket
		static class WebSocketConfiguration {

			@Bean
			@ConditionalOnMissingBean
			io.github.hjosugi.springchat.web.servlet.ChatWebSocketHandler chatWebSocketHandler(ChatEvents events,
					ChatProperties properties) {
				return new io.github.hjosugi.springchat.web.servlet.ChatWebSocketHandler(events,
						properties.maxMessageLength());
			}

			// Without allowed origins, only pages of this application may connect.
			@Bean
			WebSocketConfigurer chatWebSocketConfigurer(
					io.github.hjosugi.springchat.web.servlet.ChatWebSocketHandler handler, ChatProperties properties) {
				return (registry) -> registry.addHandler(handler, properties.socketPath());
			}

		}

		@Configuration(proxyBeanMethods = false)
		@ConditionalOnMissingClass("org.springframework.web.socket.config.annotation.WebSocketConfigurer")
		static class MissingWebSocketConfiguration {

			MissingWebSocketConfiguration(ChatProperties properties) {
				if (properties.transport() == ChatTransport.WEBSOCKET) {
					throw new IllegalStateException(
							"spring-chat.transport=websocket needs spring-boot-starter-websocket on the classpath");
				}
			}

		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnWebApplication(type = Type.REACTIVE)
	static class ReactiveConfiguration {

		@Bean
		WebFluxConfigurer chatWebFluxConfigurer(ChatProperties properties) {
			return new WebFluxConfigurer() {

				@Override
				public void configurePathMatching(
						org.springframework.web.reactive.config.PathMatchConfigurer configurer) {
					configurer.addPathPrefix(properties.path(), SpringChatWebAutoConfiguration::isChatController);
				}

			};
		}

		@Bean
		@ConditionalOnMissingBean
		io.github.hjosugi.springchat.web.reactive.ChatWebSocketHandler chatWebSocketHandler(ChatEvents events) {
			return new io.github.hjosugi.springchat.web.reactive.ChatWebSocketHandler(events);
		}

		@Bean
		HandlerMapping chatWebSocketHandlerMapping(
				io.github.hjosugi.springchat.web.reactive.ChatWebSocketHandler handler, ChatProperties properties) {
			// Ahead of the annotated controllers, as the WebFlux reference has it.
			SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping(Map.of(properties.socketPath(), handler), -1);
			// WebFlux accepts a WebSocket from any site unless told otherwise. With no
			// allowed origins, only pages of this application may connect, as on Spring MVC.
			mapping.setCorsConfigurations(Map.of(properties.socketPath(), new CorsConfiguration()));
			return mapping;
		}

	}

}

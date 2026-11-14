package io.github.hjosugi.springchat.autoconfigure;

import java.util.Map;

import io.github.hjosugi.springchat.ChatProperties;
import io.github.hjosugi.springchat.web.ChatEvents;
import io.github.hjosugi.springchat.web.reactive.ChatWebSocketHandler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.config.PathMatchConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;

/**
 * The chat on WebFlux.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.REACTIVE)
class ReactiveChatConfiguration {

	@Bean
	WebFluxConfigurer chatWebFluxConfigurer(ChatProperties properties) {
		return new WebFluxConfigurer() {

			@Override
			public void configurePathMatching(PathMatchConfigurer configurer) {
				configurer.addPathPrefix(properties.path(), SpringChatWebAutoConfiguration::isChatController);
			}

		};
	}

	@Bean
	@ConditionalOnMissingBean
	ChatWebSocketHandler chatWebSocketHandler(ChatEvents events) {
		return new ChatWebSocketHandler(events);
	}

	@Bean
	HandlerMapping chatWebSocketHandlerMapping(ChatWebSocketHandler handler, ChatProperties properties) {
		// Ahead of the annotated controllers, as the WebFlux reference has it.
		SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping(Map.of(properties.socketPath(), handler), -1);
		// WebFlux accepts a WebSocket from any site unless told otherwise. With no
		// allowed origins, only pages of this application may connect, as on Spring MVC.
		mapping.setCorsConfigurations(Map.of(properties.socketPath(), new CorsConfiguration()));
		return mapping;
	}

}

package io.github.hjosugi.springchat.autoconfigure;

import io.github.hjosugi.springchat.ChatProperties;
import io.github.hjosugi.springchat.ChatTransport;
import io.github.hjosugi.springchat.web.ChatEvents;
import io.github.hjosugi.springchat.web.ChatJson;
import io.github.hjosugi.springchat.web.ChatStreamController;
import io.github.hjosugi.springchat.web.servlet.ChatWebSocketHandler;
import io.github.hjosugi.springchat.web.servlet.ServletChatStreamController;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;

/**
 * The chat on Spring MVC.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
class ServletChatConfiguration {

	@Bean
	@ConditionalOnMissingBean(ChatStreamController.class)
	ServletChatStreamController chatStreamController(ChatEvents events, ChatJson json) {
		return new ServletChatStreamController(events, json);
	}

	@Bean
	WebMvcConfigurer chatWebMvcConfigurer(ChatProperties properties) {
		return new WebMvcConfigurer() {

			@Override
			public void configurePathMatch(PathMatchConfigurer configurer) {
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
		ChatWebSocketHandler chatWebSocketHandler(ChatEvents events, ChatJson json) {
			return new ChatWebSocketHandler(events, json);
		}

		// Without allowed origins, only pages of this application may connect.
		@Bean
		WebSocketConfigurer chatWebSocketConfigurer(ChatWebSocketHandler handler, ChatProperties properties) {
			return (registry) -> registry.addHandler(handler, properties.socketPath());
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingClass("org.springframework.web.socket.config.annotation.WebSocketConfigurer")
	static class MissingWebSocketConfiguration {

		MissingWebSocketConfiguration(ChatProperties properties) {
			if (properties.transport() == ChatTransport.WEBSOCKET) {
				throw new MissingWebSocketException();
			}
		}

	}

}

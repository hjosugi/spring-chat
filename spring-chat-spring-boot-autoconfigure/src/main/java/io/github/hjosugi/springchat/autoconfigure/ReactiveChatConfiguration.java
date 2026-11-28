package io.github.hjosugi.springchat.autoconfigure;

import java.util.Map;

import io.github.hjosugi.springchat.ChatProperties;
import io.github.hjosugi.springchat.web.ChatEvents;
import io.github.hjosugi.springchat.web.ChatJson;
import io.github.hjosugi.springchat.web.ChatStreamController;
import io.github.hjosugi.springchat.web.reactive.ChatWebSocketHandler;
import io.github.hjosugi.springchat.web.reactive.ReactiveChatStreamController;
import reactor.netty.http.server.WebsocketServerSpec;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.config.PathMatchConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.WebSocketService;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;

/**
 * The chat on WebFlux.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.REACTIVE)
class ReactiveChatConfiguration {

	@Bean
	@ConditionalOnMissingBean(ChatStreamController.class)
	ReactiveChatStreamController chatStreamController(ChatEvents events, ChatJson json) {
		return new ReactiveChatStreamController(events, json);
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass({ WebsocketServerSpec.class, NettyReactiveWebServerFactory.class })
	@ConditionalOnBooleanProperty("spring-chat.attachments.enabled")
	@ConditionalOnBean(NettyReactiveWebServerFactory.class)
	@ConditionalOnMissingBean(WebSocketService.class)
	static class NettyAttachmentConfiguration {

		@Bean
		WebSocketHandlerAdapter chatAttachmentWebSocketAdapter(ChatWebSocketHandler chat, ChatJson json,
				ObjectProvider<WebFluxConfigurer> configurers) {
			// Keep the application's handshake (including session attributes and upgrade rules)
			// when it supplies one through WebFluxConfigurer.
			boolean defaultHandshake = configurers.orderedStream()
				.allMatch((configurer) -> configurer.getWebSocketService() == null);
			var strategy = new ReactorNettyRequestUpgradeStrategy(
					() -> WebsocketServerSpec.builder().maxFramePayloadLength(json.maxRequestBytes()));
			return new WebSocketHandlerAdapter(new HandshakeWebSocketService(strategy)) {
				@Override
				public boolean supports(Object handler) {
					return defaultHandshake && handler == chat;
				}
			};
		}

	}

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
	ChatWebSocketHandler chatWebSocketHandler(ChatEvents events, ChatJson json) {
		return new ChatWebSocketHandler(events, json);
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

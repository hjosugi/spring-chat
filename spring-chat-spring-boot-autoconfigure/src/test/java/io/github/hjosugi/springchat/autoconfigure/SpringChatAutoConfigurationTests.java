package io.github.hjosugi.springchat.autoconfigure;

import java.util.List;

import io.github.hjosugi.springchat.ChatClientChatService;
import io.github.hjosugi.springchat.ChatPromptCustomizer;
import io.github.hjosugi.springchat.ChatProperties;
import io.github.hjosugi.springchat.ChatService;
import io.github.hjosugi.springchat.ChatTransport;
import io.github.hjosugi.springchat.web.BrowserMessage;
import io.github.hjosugi.springchat.web.ChatEvent;
import io.github.hjosugi.springchat.web.ChatPageController;
import io.github.hjosugi.springchat.web.ChatStreamController;
import io.github.hjosugi.springchat.web.ChatView;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;

class SpringChatAutoConfigurationTests {

	private static final AutoConfigurations autoConfigurations = AutoConfigurations
		.of(SpringChatAutoConfiguration.class, SpringChatWebAutoConfiguration.class);

	private final WebApplicationContextRunner servlet = new WebApplicationContextRunner()
		.withConfiguration(autoConfigurations);

	private final ReactiveWebApplicationContextRunner reactive = new ReactiveWebApplicationContextRunner()
		.withConfiguration(autoConfigurations);

	@Test
	void withoutAChatClientNothingIsConfigured() {
		this.servlet.run((context) -> assertThat(context).doesNotHaveBean(ChatService.class)
			.doesNotHaveBean(ChatStreamController.class));
	}

	@Test
	void theDefaultServiceAsksTheChatClient() {
		this.servlet.withUserConfiguration(ChatClientConfiguration.class)
			.run((context) -> assertThat(context).hasSingleBean(ChatClientChatService.class));
	}

	@Test
	void servletGetsBothTransportsAndThePage() {
		this.servlet.withUserConfiguration(ChatServiceConfiguration.class)
			.run((context) -> assertThat(context).hasSingleBean(ChatStreamController.class)
				.hasSingleBean(ChatPageController.class)
				.hasBean("springChat")
				.hasSingleBean(io.github.hjosugi.springchat.web.servlet.ChatWebSocketHandler.class)
				.doesNotHaveBean(io.github.hjosugi.springchat.web.reactive.ChatWebSocketHandler.class));
	}

	@Test
	void reactiveGetsBothTransportsAndThePage() {
		this.reactive.withUserConfiguration(ChatServiceConfiguration.class)
			.run((context) -> assertThat(context).hasSingleBean(ChatStreamController.class)
				.hasSingleBean(ChatPageController.class)
				.hasSingleBean(io.github.hjosugi.springchat.web.reactive.ChatWebSocketHandler.class)
				.doesNotHaveBean(io.github.hjosugi.springchat.web.servlet.ChatWebSocketHandler.class));
	}

	@Test
	void anApplicationThatIsNotWebGetsOnlyTheService() {
		new ApplicationContextRunner().withConfiguration(autoConfigurations)
			.withUserConfiguration(ChatServiceConfiguration.class)
			.run((context) -> assertThat(context).hasSingleBean(ChatService.class)
				.doesNotHaveBean(ChatStreamController.class));
	}

	@Test
	void propertiesBind() {
		this.servlet.withUserConfiguration(ChatServiceConfiguration.class)
			.withPropertyValues("spring-chat.path=/assistant", "spring-chat.transport=websocket",
					"spring-chat.page.enabled=false")
			.run((context) -> {
				ChatProperties properties = context.getBean(ChatProperties.class);
				assertThat(properties.transport()).isEqualTo(ChatTransport.WEBSOCKET);
				assertThat(properties.streamPath()).isEqualTo("/assistant/stream");
				assertThat(properties.socketPath()).isEqualTo("/assistant/ws");
				assertThat(context.getBean("springChat", ChatView.class))
					.isEqualTo(new ChatView("Chat", "websocket", "/assistant/stream", "/assistant/ws", null, "Message"));
				assertThat(context).doesNotHaveBean(ChatPageController.class);
			});
	}

	@Test
	void aPathThatWouldNotMapFailsTheStart() {
		this.servlet.withUserConfiguration(ChatServiceConfiguration.class)
			.withPropertyValues("spring-chat.path=chat/")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("spring-chat.path"));
	}

	@Test
	void disabledConfiguresNothing() {
		this.servlet.withUserConfiguration(ChatClientConfiguration.class)
			.withPropertyValues("spring-chat.enabled=false")
			.run((context) -> assertThat(context).doesNotHaveBean(ChatService.class)
				.doesNotHaveBean(ChatStreamController.class));
	}

	@Test
	void servletWebSocketTransportNeedsTheWebSocketStarter() {
		this.servlet.withUserConfiguration(ChatServiceConfiguration.class)
			.withClassLoader(new FilteredClassLoader(WebSocketConfigurer.class))
			.withPropertyValues("spring-chat.transport=websocket")
			.run((context) -> {
				assertThat(context).hasFailed()
					.getFailure()
					.rootCause()
					.isInstanceOf(MissingWebSocketException.class)
					.hasMessageContaining("spring-boot-starter-websocket");
				FailureAnalysis analysis = new MissingWebSocketFailureAnalyzer().analyze(context.getStartupFailure());
				assertThat(analysis.getAction()).contains("spring-boot-starter-websocket", "spring-chat.transport");
			});
	}

	@Test
	void withoutThymeleafTheEndpointsServeAUiOfTheApplicationsOwn() {
		this.servlet.withUserConfiguration(ChatServiceConfiguration.class)
			.withClassLoader(new FilteredClassLoader("org.thymeleaf"))
			.run((context) -> assertThat(context).hasNotFailed()
				.hasSingleBean(ChatStreamController.class)
				.hasSingleBean(io.github.hjosugi.springchat.web.servlet.ChatWebSocketHandler.class)
				.doesNotHaveBean(ChatPageController.class)
				.doesNotHaveBean("springChat"));
	}

	@Test
	void theDefaultServiceTakesTheCustomizersInOrder() {
		this.servlet.withUserConfiguration(ChatClientConfiguration.class, CustomizerConfiguration.class)
			.run((context) -> assertThat(ReflectionTestUtils.getField(context.getBean(ChatClientChatService.class),
					"customizers"))
				.isEqualTo(List.of(context.getBean("first"), context.getBean("second"))));
	}

	@Test
	void aNativeImageKeepsWhatTheJsonAndTheTemplatesRead() {
		RuntimeHints hints = new RuntimeHints();
		new ChatRuntimeHints().registerHints(hints, getClass().getClassLoader());

		for (Class<?> type : List.of(ChatEvent.class, BrowserMessage.class, ChatView.class)) {
			assertThat(RuntimeHintsPredicates.reflection().onType(type)).as(type.getName()).accepts(hints);
		}
		assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(ChatView.class, "transport")).accepts(hints);
	}

	@Test
	void servletSseWorksWithoutTheWebSocketStarter() {
		this.servlet.withUserConfiguration(ChatServiceConfiguration.class)
			.withClassLoader(new FilteredClassLoader(WebSocketConfigurer.class))
			.run((context) -> assertThat(context).hasNotFailed().hasSingleBean(ChatStreamController.class));
	}

	@Configuration(proxyBeanMethods = false)
	static class ChatServiceConfiguration {

		@Bean
		ChatService chatService() {
			return (request) -> Flux.just("hello");
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CustomizerConfiguration {

		@Bean
		@Order(2)
		ChatPromptCustomizer second() {
			return (prompt, request) -> {
			};
		}

		@Bean
		@Order(1)
		ChatPromptCustomizer first() {
			return (prompt, request) -> {
			};
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ChatClientConfiguration {

		@Bean
		ChatClient.Builder chatClientBuilder() {
			return mock(ChatClient.Builder.class, RETURNS_SELF);
		}

	}

}

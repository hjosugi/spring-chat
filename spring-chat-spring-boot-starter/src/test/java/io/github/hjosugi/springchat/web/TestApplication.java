package io.github.hjosugi.springchat.web;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.hjosugi.springchat.ChatRequest;
import io.github.hjosugi.springchat.ChatService;
import reactor.core.publisher.Flux;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * An application with the starter and a scripted {@link ChatService}. Its /embedded page
 * includes the chat fragment and has no controller, so nothing fills a model for it.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class TestApplication {

	@Bean
	ScriptedChatService chatService() {
		return new ScriptedChatService();
	}

	static class ScriptedChatService implements ChatService {

		final AtomicInteger cancelled = new AtomicInteger();

		@Override
		public Flux<String> stream(ChatRequest request) {
			return switch (request.text()) {
				case "endless" -> Flux.interval(Duration.ofMillis(1))
					.onBackpressureDrop()
					.map((tick) -> "x")
					.doOnCancel(this.cancelled::incrementAndGet);
				case "fail" -> Flux.just("partial").concatWith(Flux.error(new IllegalStateException("secret")));
				default -> Flux.just(request.text().length() + " characters: ", request.text());
			};
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnWebApplication(type = Type.SERVLET)
	static class ServletPages implements WebMvcConfigurer {

		@Override
		public void addViewControllers(ViewControllerRegistry registry) {
			registry.addViewController("/embedded").setViewName("embedded");
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnWebApplication(type = Type.REACTIVE)
	static class ReactivePages {

		// Tomcat is on the test classpath too, for the servlet tests.
		@Bean
		NettyReactiveWebServerFactory nettyReactiveWebServerFactory() {
			return new NettyReactiveWebServerFactory();
		}

		@Bean
		RouterFunction<ServerResponse> embedded() {
			return RouterFunctions.route().GET("/embedded", (request) -> ServerResponse.ok().render("embedded")).build();
		}

	}

}

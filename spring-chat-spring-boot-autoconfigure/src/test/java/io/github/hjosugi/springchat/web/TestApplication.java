package io.github.hjosugi.springchat.web;

import java.io.IOException;
import java.security.Principal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.hjosugi.springchat.ChatRequest;
import io.github.hjosugi.springchat.ChatService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
import org.springframework.web.server.WebFilter;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * An application with the starter and a scripted {@link ChatService}. Its /embedded page
 * includes the chat fragment and has no controller, so nothing fills a model for it.
 * A request with an {@value #USER} header comes from that user, as Spring Security would
 * have it.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class TestApplication {

	static final String USER = "X-Test-User";

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
				case "who" -> Flux.just("you are " + ((request.principal() != null) ? request.principal().getName() : "nobody"));
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

		@Bean
		Filter testUser() {
			return new Filter() {

				@Override
				public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
						throws IOException, ServletException {
					HttpServletRequest http = (HttpServletRequest) request;
					String user = http.getHeader(USER);
					chain.doFilter((user != null) ? new HttpServletRequestWrapper(http) {

						@Override
						public Principal getUserPrincipal() {
							return () -> user;
						}

					} : request, response);
				}

			};
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
		WebFilter testUser() {
			return (exchange, chain) -> {
				String user = exchange.getRequest().getHeaders().getFirst(USER);
				return chain.filter((user != null)
						? exchange.mutate().principal(Mono.just((Principal) () -> user)).build() : exchange);
			};
		}

		@Bean
		RouterFunction<ServerResponse> embedded() {
			return RouterFunctions.route().GET("/embedded", (request) -> ServerResponse.ok().render("embedded")).build();
		}

	}

}

package io.github.hjosugi.springchat.web;

import java.io.IOException;
import java.security.Principal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import io.github.hjosugi.springchat.ChatReply;
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

		/**
		 * What a reply holds besides text: a tool call that waits for the user, the
		 * decisions that continue it, and a reply that reached the limit.
		 */
		@Override
		public Flux<ChatReply> reply(ChatRequest request) {
			if (!request.attachments().isEmpty()) {
				var media = request.attachments().get(0);
				return Flux.just(new ChatReply.Text(media.getName() + " " + media.getMimeType() + " "
						+ Arrays.hashCode(media.getDataAsByteArray())));
			}
			if (!request.decisions().isEmpty()) {
				return Flux.just(new ChatReply.Text("decided: " + request.decisions().stream()
					.map((decision) -> decision.id() + "=" + decision.allow())
					.collect(Collectors.joining(",")) + " by " + ((request.principal() != null) ? request.principal().getName() : "nobody")));
			}
			return switch (request.text()) {
				case "progress" -> Flux.just(new ChatReply.Reasoning("Looking"), new ChatReply.ToolCall("orders"),
						new ChatReply.Text("found"), new ChatReply.Usage(7, 3, 10), new ChatReply.Ended(ChatReply.Reason.TRUNCATED));
				case "pause" -> Flux.just(new ChatReply.Text("asking"), new ChatReply.Confirmation(
						List.of(new ChatReply.PendingToolCall("call_1", "cancelOrder", "{\"orderId\":\"A-2\"}"))));
				case "cut" -> Flux.just(new ChatReply.Text("long"), new ChatReply.Ended(ChatReply.Reason.TRUNCATED));
				default -> ChatService.super.reply(request);
			};
		}

		@Override
		public Flux<String> stream(ChatRequest request) {
			return switch (request.text()) {
				case "endless" -> Flux.interval(Duration.ofMillis(1))
					.onBackpressureDrop()
					.map((tick) -> "x")
					.doOnCancel(this.cancelled::incrementAndGet);
				case "fail" -> Flux.just("partial").concatWith(Flux.error(new IllegalStateException("secret")));
				case "silent" -> Flux.empty();
				// Holds the thread that subscribes for as long as it is read, as a model does
				// that reads a blocking stream there: Spring AI's Gemini model, say.
				case "blocking" -> Flux.<String>generate((sink) -> {
					try {
						Thread.sleep(5);
						sink.next("x");
					}
					catch (InterruptedException ex) {
						sink.complete();
					}
				}).doOnCancel(this.cancelled::incrementAndGet);
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

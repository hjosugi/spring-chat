package io.github.hjosugi.springchat.sample;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.hjosugi.springchat.ChatPromptCustomizer;
import io.github.hjosugi.springchat.sample.FakeAnthropic.Request;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sample with Spring AI's real Anthropic client, against a stand-in for the API: what
 * the sample's properties send, what the model's stream turns into, the conversation
 * memory, and a tool that a {@link ChatPromptCustomizer} adds.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.model.chat=anthropic", "spring.ai.anthropic.api-key=test-key",
				"spring.ai.anthropic.max-retries=0" })
class AnthropicModelTests {

	private static final Pattern TOOL_RESULT = Pattern.compile("order [^\"\\\\]+ has shipped");

	private static final FakeAnthropic anthropic = start();

	private static final JsonMapper json = JsonMapper.shared();

	private final HttpClient http = HttpClient.newHttpClient();

	@Value("${local.server.port}")
	private int port;

	private static FakeAnthropic start() {
		try {
			return new FakeAnthropic((body) -> {
				Matcher result = TOOL_RESULT.matcher(body.toString());
				if (result.find()) {
					return FakeAnthropic.text("Good news: ", result.group(), ".");
				}
				if (body.toString().contains("a long story")) {
					return FakeAnthropic.slowText(400);
				}
				if (body.toString().contains("where is order")) {
					return FakeAnthropic.toolUse("toolu_1", "orderStatus", "{\"orderId\": \"A-1\"}");
				}
				return FakeAnthropic.text("Hello", " ", "world");
			});
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@DynamicPropertySource
	static void anthropicUrl(DynamicPropertyRegistry registry) {
		registry.add("spring.ai.anthropic.base-url", anthropic::url);
	}

	@AfterAll
	static void stop() {
		anthropic.stop();
	}

	@BeforeEach
	void forget() {
		anthropic.reset();
	}

	@Test
	void theModelsStreamBecomesTheReply() throws Exception {
		String reply = post("text-1", "hi");

		assertThat(reply).startsWith("event:start")
			.contains("\"delta\":\"Hello\"", "\"delta\":\" \"", "\"delta\":\"world\"")
			.contains("event:done")
			.doesNotContain("event:error");
	}

	@Test
	void theRequestCarriesTheSamplesSettings() throws Exception {
		post("settings-1", "hi");

		Request request = single();
		assertThat(request.path()).isEqualTo("/v1/messages");
		assertThat(request.apiKey()).isEqualTo("test-key");
		JsonNode body = request.body();
		assertThat(body.get("model").asString()).isEqualTo("claude-opus-5-5");
		assertThat(body.get("max_tokens").asInt()).isEqualTo(64000);
		assertThat(body.get("stream").asBoolean()).isTrue();
		assertThat(body.get("system").toString()).contains("You are a helpful assistant.");
		assertThat(lastUserText(body)).isEqualTo("hi");
	}

	@Test
	void aCustomizersOptionsKeepTheModelsDefaults() throws Exception {
		post("options-1", "hi");

		JsonNode body = single().body();
		assertThat(body.get("stop_sequences").toString()).isEqualTo("[\"THE END\"]");
		assertThat(body.get("model").asString()).isEqualTo("claude-opus-5-5");
		assertThat(body.get("max_tokens").asInt()).isEqualTo(64000);
	}

	@Test
	void theNextMessageSeesTheEarlierOnes() throws Exception {
		post("memory-1", "hi");
		post("memory-1", "and again");

		JsonNode messages = anthropic.requests.get(1).body().get("messages");
		assertThat(messages).hasSize(3);
		assertThat(messages.get(0).toString()).contains("\"user\"", "hi");
		assertThat(messages.get(1).toString()).contains("\"assistant\"", "Hello world");
		assertThat(messages.get(2).toString()).contains("\"user\"", "and again");
	}

	@Test
	void aToolFromACustomizerIsCalledWhileTheReplyStreams() throws Exception {
		String reply = post("tool-1", "where is order A-1?");

		List<Request> requests = anthropic.requests;
		assertThat(requests).hasSize(2);
		assertThat(requests.get(0).body().get("tools").toString()).contains("orderStatus", "orderId");
		assertThat(requests.get(1).body().toString()).contains("tool_result", "order A-1 of tool-1 has shipped");
		assertThat(reply).contains("\"delta\":\"order A-1 of tool-1 has shipped\"", "event:done")
			.doesNotContain("event:error");
		// A tool may block, so it must not run on a thread of the web server.
		// Reactor names its threads loomBoundedElastic- when they are virtual.
		assertThat(OrderTools.thread).matches("(loomB|b)oundedElastic-\\d+");
	}

	/**
	 * Spring AI 2.0.2 (and 2.1.0-RC2) closes the response from the cancelling thread
	 * while its reader thread is inside a read. OkHttp then fails with "Unbalanced
	 * enter/exit", the connection stays open and the model streams the whole reply.
	 */
	static final String SPRING_AI_DOES_NOT_CLOSE = "Spring AI's Anthropic client does not close its stream on cancel";

	@Test
	@Disabled(SPRING_AI_DOES_NOT_CLOSE)
	void stoppingOverWebSocketClosesTheModelsStream() throws Exception {
		CompletableFuture<String> firstText = new CompletableFuture<>();
		WebSocket socket = this.http.newWebSocketBuilder()
			.buildAsync(URI.create("ws://localhost:" + this.port + "/chat/ws"), new WebSocket.Listener() {

				@Override
				public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
					if (data.toString().contains("\"type\":\"text\"")) {
						firstText.complete(data.toString());
					}
					webSocket.request(1);
					return null;
				}

			})
			.get(10, TimeUnit.SECONDS);
		socket.sendText(json.writeValueAsString(
				Map.of("type", "message", "conversationId", "stop-1", "text", "tell me a long story")), true);
		firstText.get(10, TimeUnit.SECONDS);

		socket.sendText("{\"type\":\"stop\"}", true);

		// Without the stop the reply would take 20 seconds more.
		anthropic.clientGone.get(5, TimeUnit.SECONDS);
		socket.abort();
	}

	@Test
	@Disabled(SPRING_AI_DOES_NOT_CLOSE)
	void leavingAnSseReplyClosesTheModelsStream() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/chat/stream"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(
					json.writeValueAsString(Map.of("conversationId", "leave-1", "text", "tell me a long story"))))
			.build();
		InputStream body = this.http.send(request, HttpResponse.BodyHandlers.ofInputStream()).body();
		String received = "";
		while (!received.contains("event:text")) {
			received += new String(body.readNBytes(64));
		}

		body.close();

		anthropic.clientGone.get(5, TimeUnit.SECONDS);
	}

	private Request single() {
		assertThat(anthropic.requests).hasSize(1);
		return anthropic.requests.get(0);
	}

	private static String lastUserText(JsonNode body) {
		JsonNode content = body.get("messages").get(body.get("messages").size() - 1).get("content");
		return content.isString() ? content.asString() : content.get(0).get("text").asString();
	}

	private String post(String conversationId, String text) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/chat/stream"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers
				.ofString(json.writeValueAsString(Map.of("conversationId", conversationId, "text", text))))
			.build();
		// With a limit, so that a reply that never ends fails its test and does not hold the build.
		HttpResponse<String> response = this.http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.get(30, TimeUnit.SECONDS);
		assertThat(response.statusCode()).isEqualTo(200);
		return response.body();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class OrderToolsConfiguration {

		@Bean
		ChatPromptCustomizer orderTools() {
			return (prompt, request) -> prompt.tools(new OrderTools())
				.toolContext(Map.of("conversation", request.conversationId()))
				.options(ChatOptions.builder().stopSequences(List.of("THE END")));
		}

	}

	static class OrderTools {

		static volatile String thread;

		@Tool(description = "Tells where an order is")
		String orderStatus(@ToolParam(description = "The order id") String orderId, ToolContext context) {
			thread = Thread.currentThread().getName();
			return "order " + orderId + " of " + context.getContext().get("conversation") + " has shipped";
		}

	}

}

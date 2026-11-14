package io.github.hjosugi.springchat.sample;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SampleApplicationTests {

	private final HttpClient http = HttpClient.newHttpClient();

	@Value("${local.server.port}")
	private int port;

	@Test
	void sseStreamsTheReply() throws Exception {
		HttpResponse<String> response = post("/chat/stream", """
				{"conversationId": "sse-1", "text": "hello there"}""");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				(type) -> assertThat(type).startsWith("text/event-stream"));
		assertThat(response.body()).startsWith("event:start")
			.contains("event:text", "\"delta\":\"**hello \"")
			.contains("event:done")
			.doesNotContain("event:error");
	}

	@Test
	void sseRemembersTheConversation() throws Exception {
		post("/chat/stream", """
				{"conversationId": "sse-2", "text": "one"}""");
		HttpResponse<String> second = post("/chat/stream", """
				{"conversationId": "sse-2", "text": "two"}""");

		assertThat(second.body()).contains("\"delta\":\"2 \"");
	}

	@Test
	void sseRejectsAnEmptyMessage() throws Exception {
		HttpResponse<String> response = post("/chat/stream", """
				{"conversationId": "sse-3", "text": " "}""");

		assertThat(response.body()).contains("event:error").doesNotContain("event:text");
	}

	@Test
	void webSocketStreamsTheReply() throws Exception {
		Frames frames = new Frames();
		WebSocket socket = this.http.newWebSocketBuilder()
			.buildAsync(URI.create("ws://localhost:" + this.port + "/chat/ws"), frames)
			.get(10, TimeUnit.SECONDS);
		socket.sendText("""
				{"type": "message", "conversationId": "ws-1", "text": "hello there"}""", true);

		assertThat(frames.done.get(10, TimeUnit.SECONDS)).startsWith("{\"type\":\"start\"}")
			.contains("{\"type\":\"text\",\"delta\":\"**hello \"}")
			.endsWith("{\"type\":\"done\"}");
		socket.abort();
	}

	@Test
	void pagesRenderTheChat() throws Exception {
		assertThat(get("/chat")).contains("<deep-chat", "data-transport=\"sse\"", "data-stream-url=\"/chat/stream\"",
				"/dist/deepChat.bundle.js\"");
		assertThat(get("/?transport=websocket")).contains("data-transport=\"websocket\"",
				"data-socket-url=\"/chat/ws\"");
		assertThat(get("/spring-chat/spring-chat.js")).contains("deep-chat.spring-chat");
		assertThat(get("/webjars/deep-chat/dist/deepChat.bundle.js")).contains("customElements.define(\"deep-chat\"");
	}

	private HttpResponse<String> post(String path, String json) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(uri(path))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json))
			.build();
		return this.http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private String get(String path) throws Exception {
		HttpResponse<String> response = this.http.send(HttpRequest.newBuilder(uri(path)).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(path).isEqualTo(200);
		return response.body();
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + this.port + path);
	}

	/**
	 * Collects the frames of one reply.
	 */
	private static final class Frames implements WebSocket.Listener {

		private final StringBuilder received = new StringBuilder();

		private final CompletableFuture<String> done = new CompletableFuture<>();

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			this.received.append(data);
			if (this.received.toString().endsWith("{\"type\":\"done\"}") || this.received.indexOf("\"error\"") >= 0) {
				this.done.complete(this.received.toString());
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			this.done.completeExceptionally(error);
		}

	}

}

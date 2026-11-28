package io.github.hjosugi.springchat.web;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.github.hjosugi.springchat.web.TestApplication.ScriptedChatService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Both transports and the pages, against a running server. The subclasses choose the web stack.
 */
abstract class AbstractTransportTests {

	private static final String START = "{\"type\":\"start\"}";

	private static final String DONE = "{\"type\":\"done\"}";

	private static final String CONFIRM = "{\"type\":\"confirm\",\"calls\":[{\"id\":\"call_1\","
			+ "\"name\":\"cancelOrder\",\"arguments\":\"{\\\"orderId\\\":\\\"A-2\\\"}\"}]}";

	private static final Duration REPLY_TIMEOUT = Duration.ofSeconds(30);

	private static final JsonMapper json = JsonMapper.shared();

	private final HttpClient http = HttpClient.newHttpClient();

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private ScriptedChatService chatService;

	/**
	 * Where the application under test has the chat.
	 */
	protected String path() {
		return "/chat";
	}

	@Test
	void bothTransportsCarryProgressAndUsageOnTheLastEvent() throws Exception {
		List<String> expected = List.of(START, "{\"type\":\"reasoning\",\"delta\":\"Looking\"}",
				"{\"type\":\"tool\",\"name\":\"orders\"}", text("found"),
				"{\"type\":\"done\",\"reason\":\"truncated\",\"usage\":{\"promptTokens\":7,\"completionTokens\":3,\"totalTokens\":10}}");
		assertThat(post(message("progress")).body().lines().filter((line) -> line.startsWith("data:"))
			.map((line) -> line.substring(5))).containsExactlyElementsOf(expected);
		SocketFrames frames = new SocketFrames();
		WebSocket socket = connect(frames, null);
		try {
			socket.sendText(message("progress"), true).join();
			assertThat(frames.through(expected.get(expected.size() - 1))).containsExactlyElementsOf(expected);
		}
		finally {
			socket.abort();
		}
	}

	@Test
	void sseStreamsTheReply() throws Exception {
		HttpResponse<String> response = post(message("hello"));

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).get().asString().startsWith("text/event-stream");
		assertThat(response.body().lines().filter((line) -> !line.isEmpty())).containsExactly("event:start",
				"data:" + START, "event:text", "data:" + text("5 characters: "), "event:text", "data:" + text("hello"),
				"event:done", "data:" + DONE);
	}

	@Test
	void sseEndsAReplyWithoutAWordWithDone() throws Exception {
		assertThat(post(message("silent")).body().lines().filter((line) -> !line.isEmpty()))
			.containsExactly("event:start", "data:" + START, "event:done", "data:" + DONE);
	}

	@Test
	void sseTellsProxiesNotToHoldTheStreamBack() throws Exception {
		HttpResponse<String> response = post(message("hello"));

		assertThat(response.headers().firstValue("X-Accel-Buffering")).hasValue("no");
		assertThat(response.headers().firstValue("Cache-Control")).get().asString().contains("no-transform");
	}

	@Test
	void sseEndsAFailedReplyWithAnErrorThatHidesTheCause() throws Exception {
		HttpResponse<String> response = post(message("fail"));

		assertThat(response.body()).contains("data:" + text("partial"), "event:error")
			.doesNotContain("secret", "event:done");
	}

	@Test
	void sseEndsAReplyThatWaitsForADecisionWithTheCallsToAllow() throws Exception {
		HttpResponse<String> response = post(message("pause"));

		assertThat(response.body().lines().filter((line) -> !line.isEmpty())).containsExactly("event:start",
				"data:" + START, "event:text", "data:" + text("asking"), "event:confirm",
				"data:" + CONFIRM);
	}

	@Test
	void sseTakesTheDecisionsOnAPausedReply() throws Exception {
		HttpResponse<String> response = post(decisions(true), "alice");

		assertThat(response.body()).contains("data:" + text("decided: call_1=true by alice"), "event:done")
			.doesNotContain("event:error");
	}

	@Test
	void sseTellsWhyAReplyEndedBeforeItsEnd() throws Exception {
		assertThat(post(message("cut")).body()).contains("data:" + text("long"),
				"data:{\"type\":\"done\",\"reason\":\"truncated\"}");
	}

	@Test
	void sseRejectsDecisionsThatAreNotValid() throws Exception {
		for (String body : new String[] { json.writeValueAsString(Map.of("conversationId", "c1", "decisions", List.of(Map.of("allow", true)))),
				json.writeValueAsString(Map.of("conversationId", "c1", "text", "hi", "decisions", List.of(Map.of("id", "call_1", "allow", true)))),
				json.writeValueAsString(Map.of("conversationId", "c1", "decisions", Collections.nCopies(33, Map.of("id", "call_1", "allow", true)))) }) {
			assertThat(post(body).body()).contains("event:error").doesNotContain("event:text", "event:confirm");
		}
	}

	@Test
	void sseRejectsAMessageWithoutText() throws Exception {
		assertThat(post("{\"conversationId\": \"c1\"}").body()).contains("event:start", "event:error")
			.doesNotContain("event:text");
		assertThat(post("not json").body()).contains("event:error");
	}

	@Test
	void sseTellsTheServiceWhoAsked() throws Exception {
		assertThat(post(message("who"), "alice").body()).contains("data:" + text("you are alice"));
		assertThat(post(message("who"), null).body()).contains("data:" + text("you are nobody"));
	}

	@Test
	void webSocketTellsTheServiceWhoAsked() throws Exception {
		for (String user : new String[] { "alice", null }) {
			SocketFrames frames = new SocketFrames();
			WebSocket socket = connect(frames, null, user);
			socket.sendText(message("who"), true).join();

			assertThat(frames.through(DONE)).contains(text("you are " + ((user != null) ? user : "nobody")));
			socket.abort();
		}
	}

	@Test
	void webSocketPausesAReplyAndTakesTheDecisions() throws Exception {
		SocketFrames frames = new SocketFrames();
		WebSocket socket = connect(frames, null, "alice");

		socket.sendText(message("pause"), true).join();
		assertThat(frames.through(CONFIRM)).containsExactly(START, text("asking"), CONFIRM);

		socket.sendText(json.writeValueAsString(Map.of("type", "message", "conversationId", "c1", "decisions",
				List.of(Map.of("id", "call_1", "allow", false)))), true).join();
		assertThat(frames.through(DONE)).contains(text("decided: call_1=false by alice"));
		socket.abort();
	}

	@Test
	void webSocketStreamsTheReply() throws Exception {
		SocketFrames frames = new SocketFrames();
		WebSocket socket = connect(frames, null);
		socket.sendText(message("hello"), true).join();

		assertThat(frames.through(DONE)).containsExactly(START, text("5 characters: "), text("hello"), DONE);
		socket.abort();
	}

	@Test
	void webSocketStopEndsTheReplyAndTheNextOneIsWhole() throws Exception {
		SocketFrames frames = new SocketFrames();
		WebSocket socket = connect(frames, null);
		int before = this.chatService.cancelled.get();

		for (int round = 0; round < 30; round++) {
			socket.sendText(message("endless"), true).join();
			frames.through(text("x"));
			socket.sendText("{\"type\":\"stop\"}", true).join();
			socket.sendText(message("round " + round), true).join();

			// What was on its way before the stop may still arrive; nothing of it after the next start.
			List<String> received = frames.through(DONE);
			assertThat(received.subList(received.lastIndexOf(START), received.size())).containsExactly(START,
					text("%d characters: ".formatted(("round " + round).length())), text("round " + round), DONE);
		}

		assertThat(frames.closed).isNotDone();
		await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> assertThat(this.chatService.cancelled.get() - before).isEqualTo(30));
		socket.abort();
	}

	@Test
	void webSocketStopReachesAReplyThatHoldsTheThreadItWasAskedOn() throws Exception {
		SocketFrames frames = new SocketFrames();
		WebSocket socket = connect(frames, null);
		int before = this.chatService.cancelled.get();
		socket.sendText(message("blocking"), true).join();
		frames.through(text("x"));

		socket.sendText("{\"type\":\"stop\"}", true).join();
		socket.sendText(message("hello"), true).join();

		assertThat(frames.through(DONE)).contains(text("hello"));
		await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> assertThat(this.chatService.cancelled.get() - before).isEqualTo(1));
		socket.abort();
	}

	@Test
	void sseStreamsAReplyThatHoldsTheThreadItWasAskedOnAsItComes() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(uri("http", path() + "/stream"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(message("blocking")))
			.build();
		int before = this.chatService.cancelled.get();
		InputStream body = this.http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
			.get(REPLY_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
			.body();
		String received = "";
		while (!received.contains("\"delta\":\"x\"")) {
			received += new String(body.readNBytes(64), StandardCharsets.UTF_8);
		}

		// Leaving ends it, at the next event the server tries to write.
		body.close();

		await().atMost(Duration.ofSeconds(10))
			.untilAsserted(() -> assertThat(this.chatService.cancelled.get() - before).isEqualTo(1));
	}

	@Test
	void webSocketTakesAMessageAtTheLengthLimit() throws Exception {
		SocketFrames frames = new SocketFrames();
		WebSocket socket = connect(frames, null);
		// Escaped as JSON, the line breaks make the frame longer than the message.
		socket.sendText(message("あ".repeat(7000) + "\n".repeat(1000)), true).join();

		assertThat(frames.through(DONE)).startsWith(START, text("8000 characters: "));
		socket.abort();
	}

	@Test
	void webSocketAcceptsThePagesOfThisApplicationOnly() throws Exception {
		SocketFrames frames = new SocketFrames();
		WebSocket sameOrigin = connect(frames, "http://localhost:" + this.port);
		sameOrigin.sendText(message("hello"), true).join();
		assertThat(frames.through(DONE)).contains(text("hello"));
		sameOrigin.abort();

		assertThatThrownBy(() -> connect(new SocketFrames(), "https://elsewhere.example"))
			.hasCauseInstanceOf(WebSocketHandshakeException.class)
			.cause()
			.extracting((ex) -> ((WebSocketHandshakeException) ex).getResponse().statusCode())
			.isEqualTo(403);
	}

	@Test
	void theBuiltInPageHasTheChat() throws Exception {
		assertThat(get(path())).contains("<title>Chat</title>", "<deep-chat", "data-transport=\"sse\"",
				"data-stream-url=\"" + path() + "/stream\"", "data-socket-url=\"" + path() + "/ws\"",
				"/spring-chat/spring-chat.css", "/spring-chat/spring-chat.js", "/dist/deepChat.bundle.js");
	}

	@Test
	void aPageWithoutAControllerCanIncludeTheChat() throws Exception {
		String page = get("/embedded");

		assertThat(page.substring(page.indexOf("by-property"), page.indexOf("by-include")))
			.contains("data-transport=\"sse\"");
		assertThat(page.substring(page.indexOf("by-include"))).contains("data-transport=\"websocket\"");
	}

	@Test
	void theScriptsAreServed() throws Exception {
		assertThat(get("/spring-chat/spring-chat.js")).contains("deep-chat.spring-chat");
		assertThat(get("/spring-chat/spring-chat.css")).contains("--spring-chat-primary");
		assertThat(get("/webjars/deep-chat/dist/deepChat.bundle.js")).contains("customElements.define(\"deep-chat\"");
	}

	protected final HttpResponse<String> post(String body) throws Exception {
		return post(body, null);
	}

	protected final HttpResponse<String> post(String body, String user) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri("http", path() + "/stream"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body));
		if (user != null) {
			request.header(TestApplication.USER, user);
		}
		// With a limit, so that a reply that never ends fails its test and does not hold the build.
		return this.http
			.sendAsync(request.build(),
					(response) -> HttpResponse.BodySubscribers.mapping(
							HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), AbstractTransportTests::fields))
			.get(REPLY_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
	}

	/**
	 * A server may write one space after the colon of a field, and a browser drops it.
	 * Spring Framework 7.1 writes it after {@code data}; earlier versions wrote none.
	 */
	private static String fields(String events) {
		return events.replaceAll("(?m)^(\\w+): ", "$1:");
	}

	protected final String get(String path) throws Exception {
		HttpResponse<String> response = send(path);
		assertThat(response.statusCode()).as(path).isEqualTo(200);
		return response.body();
	}

	protected final HttpResponse<String> send(String path) throws Exception {
		return this.http.send(HttpRequest.newBuilder(uri("http", path)).build(), HttpResponse.BodyHandlers.ofString());
	}

	protected final WebSocket connect(SocketFrames frames, String origin) throws Exception {
		return connect(frames, origin, null);
	}

	protected final WebSocket connect(SocketFrames frames, String origin, String user) throws Exception {
		WebSocket.Builder builder = this.http.newWebSocketBuilder();
		if (origin != null) {
			builder.header("Origin", origin);
		}
		if (user != null) {
			builder.header(TestApplication.USER, user);
		}
		return builder.buildAsync(uri("ws", path() + "/ws"), frames).get(10, TimeUnit.SECONDS);
	}

	protected static String decisions(boolean allow) {
		return json.writeValueAsString(
				Map.of("conversationId", "c1", "decisions", List.of(Map.of("id", "call_1", "allow", allow))));
	}

	protected static String message(String text) {
		return json.writeValueAsString(Map.of("type", "message", "conversationId", "c1", "text", text));
	}

	protected static String text(String delta) {
		return "{\"type\":\"text\",\"delta\":" + json.writeValueAsString(delta) + "}";
	}

	private URI uri(String scheme, String path) {
		return URI.create(scheme + "://localhost:" + this.port + path);
	}

}

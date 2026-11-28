# spring-chat

An AI chat for Spring Boot 4 and Thymeleaf. Add one dependency and a model, and the application has a chat page; one line puts the chat into a page of its own.

- **Spring AI** answers, through the application's `ChatClient`, with any model Spring AI supports.
- **SSE or WebSocket** carries the reply, chosen by a property.
- **Spring MVC or WebFlux**: the same starter configures itself for either.
- **No React and no build step**: the UI is the [Deep Chat](https://deepchat.dev) web component, served from a WebJar.

| spring-chat | Spring Boot | Spring AI | Java | Kotlin |
| --- | --- | --- | --- | --- |
| 0.1 | 4.2 | 2.1 | 17 or later | 2.4, the version Spring Boot manages; only `spring-chat-kotlin` needs it, from 0.1.2 |

## Use it

Add the starter, a web stack and a model:

```xml
<dependency>
    <groupId>io.github.hjosugi</groupId>
    <artifactId>spring-chat-spring-boot-starter</artifactId>
    <version>0.1.2</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId> <!-- or spring-boot-starter-webflux -->
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId> <!-- or any other model starter -->
</dependency>
```

The starter is on [Maven Central](https://central.sonatype.com/artifact/io.github.hjosugi/spring-chat-spring-boot-starter). With Gradle:

```kotlin
implementation("io.github.hjosugi:spring-chat-spring-boot-starter:0.1.2")
```

Start the application and open <http://localhost:8080/chat>.

To put the chat into a template of the application, include the fragment. It fills its container:

```html
<div style="height: 32rem" th:insert="~{spring-chat/chat :: chat}"></div>
```

## SSE or WebSocket

```properties
spring-chat.transport=websocket   # default: sse
```

One include can name its transport instead of using the property:

```html
<div style="height: 32rem" th:insert="~{spring-chat/chat :: chat-over('websocket')}"></div>
```

On Spring MVC, WebSocket also needs `spring-boot-starter-websocket`; without it the application fails at startup and says so. WebFlux needs nothing more.

| | SSE | WebSocket |
| --- | --- | --- |
| Endpoint | `POST /chat/stream` | `/chat/ws` |
| Browser sends | `conversationId`, `text` and optional `attachments` in a JSON body | The same JSON with `"type": "message"`, or `{"type": "stop"}` |
| Decisions | `{"conversationId", "decisions": [{"id", "allow"}]}` as the request body | `{"type": "message", "conversationId", "decisions": [...]}` |
| Stop | closes the request | sends `stop`; the connection stays |

Both transports send a `start` event, followed by `text` events with a `delta` and periodic `ping` events. The stream can also carry `reasoning` with a displayable `delta`, or `tool` with the requested tool's `name`. The page shows these while waiting for the answer. A tool request can still require confirmation. Each reply has one final event.

- `done` optionally includes the model's `usage` with `promptTokens`, `completionTokens` and `totalTokens`. A `reason` of `truncated` means the model reached its length limit. A `reason` of `filtered` means the model or its provider stopped the reply.
- `confirm`, with the `calls` (`id`, `name`, `arguments`) that the model asked for and that the user has to allow. Nothing has run. See [Tools that need the user's confirmation](#tools-that-need-the-users-confirmation).
- `error` with a `message`.

## Properties

| Property | Default | |
| --- | --- | --- |
| `spring-chat.enabled` | `true` | Whether to configure the chat |
| `spring-chat.path` | `/chat` | The built-in page; SSE is at `/stream` and WebSocket at `/ws` below it |
| `spring-chat.transport` | `sse` | `sse` or `websocket` |
| `spring-chat.page.enabled` | `true` | Whether to serve the built-in page. The fragment works without it |
| `spring-chat.title` | `Chat` | Heading of the built-in page |
| `spring-chat.intro` | | First message shown in an empty chat |
| `spring-chat.placeholder` | `Message` | Placeholder of the input |
| `spring-chat.system-prompt` | | System prompt |
| `spring-chat.memory.enabled` | `true` | Whether replies see the earlier messages of their conversation |
| `spring-chat.memory.max-messages` | `20` | How many of the latest messages a reply sees, a whole turn more at most; `0` for all |
| `spring-chat.memory.time-to-live` | `24h` | How long a conversation is kept, when the application has no session repository of its own |
| `spring-chat.max-message-length` | `8000` | Longest accepted message, in characters |
| `spring-chat.attachments.enabled` | `false` | Allow files in user messages |
| `spring-chat.attachments.max-count` | `4` | Maximum files in one message |
| `spring-chat.attachments.max-size` | `1MB` | Maximum decoded size of each file |
| `spring-chat.attachments.allowed-types` | `image/png,image/jpeg,application/pdf` | Allowed MIME types. Ranges such as `audio/*` are supported |

As environment variables they are `SPRINGCHAT_PATH`, `SPRINGCHAT_TRANSPORT` and so on: Spring Boot drops the dash.

The model is configured as Spring AI documents it, for example `spring.ai.anthropic.api-key`.

### Attachments

Enable `spring-chat.attachments.enabled` to show the file picker. The page offers a camera for allowed photo formats and a microphone when the browser's recording format is allowed. Choose MIME types your model accepts. A message can contain files alone or include a caption.

```json
{
  "conversationId": "colors",
  "text": "What color is this pixel?",
  "attachments": [{
    "name": "pixel.png",
    "mediaType": "image/png",
    "data": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAACklEQVR4nGNgAAAAAgABSK+kcQAAAABJRU5ErkJggg=="
  }]
}
```

Send the file bytes as base64 without a data URL prefix. Both transports validate the files before calling the service. The default service passes their names, types and bytes to Spring AI as user media. With memory enabled, later turns see the media kept with the original user message. Tool decisions travel in their own message.

The starter sizes the chat's HTTP reader and its default Tomcat and Reactor Netty WebSocket handling for the configured limits, including base64 overhead. Applications with a custom WebSocket handshake set their own frame limit. A reverse proxy must also accept the resulting body or frame size.

### Page messages

Set the page’s own labels and status messages with `spring-chat.messages.*`. For example, these settings localize the buttons, the HTTP error and the tool status.

```properties
spring-chat.messages.allow=許可
spring-chat.messages.deny=許可しない
spring-chat.messages.refused=サーバーがメッセージを拒否しました（HTTP {status}）。
spring-chat.messages.tool-call={name} を呼び出しています…
```

| Message keys | Where the page displays them |
| --- | --- |
| `interrupted`, `unreachable`, `refused` | A lost connection, an unreachable server, or an HTTP error. `refused` accepts `{status}` |
| `truncated`, `filtered`, `empty` | The note below an incomplete or empty reply |
| `confirm`, `allow`, `deny` | The introduction and buttons for tool confirmation |
| `tool-call` | Tool progress. `{name}` is the requested tool |
| `attachment-error` | A selected file exceeds the local limits or cannot be read |

## Change what answers

The default answer is a call to Spring AI's `ChatClient` with the system prompt and the conversation memory, which keeps each conversation as a session of Spring AI (see Conversations). A `ChatPromptCustomizer` adds tools, advisors or options to each call and keeps the rest:

```java
@Bean
ChatPromptCustomizer orderTools(OrderTools orderTools) {
    return (prompt, request) -> prompt.tools(orderTools);
}
```

`request.principal()` is who sent the message, over either transport and either web stack: with Spring Security it is their `Authentication`, and `null` if they did not sign in. A tool that acts for the user can have it in its tool context:

```java
// The chat is behind a login, so there is always a principal.
return (prompt, request) -> prompt.tools(orderTools)
    .toolContext(Map.of("user", request.principal().getName()));
```

To answer with something else entirely, declare a `ChatService`. It replaces the default, system prompt and memory included:

```java
@Bean
ChatService chatService(ChatClient.Builder builder) {
    ChatClient client = builder.build();
    return (request) -> client.prompt().user(request.text()).stream().content();
}
```

Colors are CSS variables (`--spring-chat-primary` and the others at the top of `spring-chat.css`) and follow the system's light or dark scheme.

## Tools that need the user's confirmation

A tool that does something the user may want to approve first, such as cancelling an order, says so, and Spring AI pauses the reply when the model calls it:

```java
@Tool(description = "Cancels an order of the signed-in user", requiresConfirmation = true)
String cancelOrder(String orderId, ToolContext context) { ... }
```

The chat ends the reply with a `confirm` event that lists the calls, and nothing has run. The page shows them with an Allow and a Do not allow button, and the answer goes back as a message with `decisions`, over the same transport. The calls the user allowed run, and the model answers their results, in the rest of the reply. A call without a decision is denied.

The paused reply is read from the conversation's session on the server, never from the browser, so a browser cannot make up a tool call. A decision that comes from anyone but the user of the conversation, or that arrives a second time, or after the user wrote another message, runs nothing and ends with an error. The calls are shown as text: the model makes up their names and arguments.

A custom `ChatService` can emit `ChatReply.Reasoning` and `ChatReply.ToolCall` alongside `ChatReply.Text`. Emit `ChatReply.Usage` before completion or `ChatReply.Ended` to include token counts on `done`. Emit `ChatReply.Confirmation` to ask for a tool decision. A text-only service can implement just `ChatService.stream`.

## Kotlin

The Java API works from Kotlin as it is. A lambda is a `ChatPromptCustomizer` or a `ChatService`, `ChatRequest("id", "text")` has a `Principal?`, and a `when` over the sealed `ChatReply` needs no `else`. The nullness is JSpecify's, which Kotlin 2.1 and later read as strict: `ChatRequest("id", null)` does not compile. [`JavaApiFromKotlinTests`](spring-chat-kotlin/src/test/kotlin/io/github/hjosugi/springchat/JavaApiFromKotlinTests.kt) compiles each of these.

```kotlin
@Bean
fun orderTools(orderTools: OrderTools) = ChatPromptCustomizer { prompt, _ -> prompt.tools(orderTools) }

fun describe(reply: ChatReply): String = when (reply) {
    is ChatReply.Text -> reply.delta
    is ChatReply.Reasoning -> reply.delta
    is ChatReply.ToolCall -> "requesting ${reply.name}"
    is ChatReply.Usage -> "${reply.totalTokens} tokens"
    is ChatReply.Confirmation -> "waits for you to allow ${reply.calls.map { it.name }}"
    is ChatReply.Ended -> "ended early: ${reply.reason}"
}
```

`spring-chat-kotlin` provides the reply as a `Flow` and the whole text of a reply from a suspend function.

```xml
<dependency>
    <groupId>io.github.hjosugi</groupId>
    <artifactId>spring-chat-kotlin</artifactId>
    <version>0.1.2</version>
</dependency>
```

```kotlin
@RestController
class AskController(private val chat: ChatService) {

    // The reply as server-sent events, a piece of text for each event.
    @GetMapping("/ask", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun ask(@RequestParam text: String): Flow<String> = chat.streamFlow(ChatRequest("ask", text))

    // The whole reply, without a blocked thread.
    @GetMapping("/answer")
    suspend fun answer(@RequestParam text: String): String = chat.complete(ChatRequest("answer", text))
}
```

They are extension functions of `ChatService`, in its package, so that they need no import:

| | |
| --- | --- |
| `streamFlow(request): Flow<String>` | The pieces of text of `stream` |
| `replyFlow(request): Flow<ChatReply>` | The `ChatReply`s of `reply`, to match with `when` |
| `complete(request): String` | Suspends until the reply is written, and returns its text |

A flow is cold: the service is asked when it is collected, once for each collection. Cancelling the collector, or the coroutine that waits in `complete`, cancels the reply's stream, as a stop does. An error of the service ends the collection with that error. `complete` returns the text and nothing else: where a reply paused for a confirmation or ended early, use `replyFlow`. [spring-chat-sample-kotlin](spring-chat-sample-kotlin) shows all of it.

## Conversations

A conversation is a session of Spring AI. Without a session repository in the application they are kept in memory: they are lost when the application stops, and expire after `spring-chat.memory.time-to-live`. For conversations that outlast the application, or that several instances share, add Spring AI's JDBC session starter, and the chat uses its `SessionService` as it is:

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-session-jdbc</artifactId>
</dependency>
```

The browser makes up the conversation id, so it proves nothing about who sends it. The session's id therefore holds the signed-in user as well, as a hash, and two users who send the same conversation id have two conversations. The session is labeled with the user's name (`SessionAttributes.PRINCIPAL_NAME`), so that `findByMetadata` lists the conversations of one user, and `delete` removes them.

The `ChatMemory` of earlier versions is not used any more: Spring AI deprecates it for removal in 3.0.

## A UI of your own

Depend on `spring-chat-spring-boot-autoconfigure` in place of the starter. The application then has the two endpoints and no page, Thymeleaf or Deep Chat: the events above are all a UI needs.

## With Spring Security

The endpoints are open: anyone who can reach them can spend the model's tokens. Protect `spring-chat.path` and what is below it as any other route. The chat's own files are static, at `/spring-chat/**` and `/webjars/**`.

- **CSRF on Spring MVC** needs nothing: the fragment sends the token.
- **CSRF on WebFlux** needs the `@ControllerAdvice` from [Spring Security's reference](https://docs.spring.io/spring-security/reference/reactive/exploits/csrf.html), which makes the token available to templates. Without it the SSE transport gets a 403. The WebSocket transport does not need it.
- **WebSocket** accepts connections from the application's own pages only.

## Behind a reverse proxy

- If the proxy ends TLS or changes the host, set `server.forward-headers-strategy=framework`. Otherwise the application does not know its public address and takes its own pages for another site's: the WebSocket transport is refused with a 403.
- The SSE response asks proxies not to buffer or compress it, and nginx obeys. The pings keep a quiet reply from being cut as an idle connection.

## Good to know

- **A conversation lasts as long as the page.** The browser makes up the conversation id on load, so a reload starts a new conversation.
- **Old conversations are removed** after `spring-chat.memory.time-to-live`, when they are kept in memory. With a session repository of your own, sessions are not swept by Spring AI: call `SessionService.deleteExpiredSessions` from a scheduler.
- **The model is asked on a worker thread.** Some models read their whole stream on the thread that subscribes, as Spring AI's Gemini model does. On a thread of the web server that would hold the reply back until its end, and no stop would reach it.
- **Virtual threads work**, on Java 21 or later, where Spring Boot offers them. With `spring.threads.virtual.enabled=true` the web server runs on them, and Reactor's `boundedElastic`, where the chat asks the model and sends over WebSocket, runs on them with `-Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=true`. `./mvnw verify -Pvirtual-threads` runs the tests that way. The chat holds no `synchronized` lock while it waits for the network, which on Java 21 would keep a virtual thread on its carrier thread; a tool or a model client of your own may.
- **A reply that was cut says so.** The chat reads the finish reason of the model, and the page tells the user that the reply reached the length limit or was stopped by the model. A reply without a word, which is how Anthropic's model can refuse, shows that the model gave no answer.
- **A conversation has one reply at a time.** Spring AI's session does not know whether a reply is still being written, and two that run at once mix their messages up. A new message ends the reply that is still being written for its conversation, as a stop would, over either transport. Decisions that arrive while a reply is being written are refused.
- **Errors show one fixed sentence.** The cause is in the server log only, because it can name the provider or a key.
- **A stopped reply can go on at the provider.** The chat cancels the reply and the browser sees it stop, but Spring AI 2.0.2 and 2.1.0 do not always close their connection to the provider. Over HTTP/1.1 it stays open with the models of Anthropic, where the log shows `Unbalanced enter/exit`, of OpenAI and of Google: the model writes the rest of the reply, and it is billed. A ceiling on the length of a reply limits what that costs. [spring-chat-sample](https://github.com/hjosugi/spring-chat-sample#stopping-a-reply-does-not-always-end-it-at-the-provider) has the table by provider and the tests that show it.
- **Native images** get the hints the chat needs, but no native build has been tried yet.

## Samples

```bash
./mvnw install
./mvnw -pl spring-chat-sample-webmvc spring-boot:run    # or spring-chat-sample-webflux, or spring-chat-sample-kotlin
```

Open <http://localhost:8080>. The page has links for both transports and for the built-in page.

No API key is needed: without one, a demo model repeats the message. For Claude:

```bash
CHAT_MODEL=anthropic ANTHROPIC_API_KEY=... ./mvnw -pl spring-chat-sample-webmvc spring-boot:run
```

## Layout

```
spring-chat-spring-boot-autoconfigure/  the code
  .../springchat/                       what applications use: ChatService, ChatPromptCustomizer, the properties
  .../springchat/autoconfigure/         the auto-configuration
  .../springchat/web/                   the events, SSE and the page, shared by MVC and WebFlux
  .../springchat/web/servlet/           WebSocket on Spring MVC
  .../springchat/web/reactive/          WebSocket on WebFlux
  resources/templates/spring-chat/      the fragment and the built-in page
  resources/static/spring-chat/         the script that connects Deep Chat, and the styles
spring-chat-spring-boot-starter/        dependencies only: the code, Thymeleaf and Deep Chat
spring-chat-kotlin/                     Kotlin extensions: the reply as a Flow, and its text from a suspend function
spring-chat-sample-webmvc/              the chat in a Spring MVC application
spring-chat-sample-webflux/             the same in a WebFlux application
spring-chat-sample-kotlin/              the same in a Kotlin WebFlux application, with coroutine endpoints
```

Applications use `io.github.hjosugi.springchat` only; the packages below it may change between versions.

`./mvnw verify` runs the tests. The library's start a server on each web stack and talk to it over real SSE and WebSocket connections; the samples' do the same through Spring AI, with the demo model.

To investigate an intermittent WebFlux SSE response that reaches `done` and then hangs, build the WebFlux sample from the revision under test and pass its executable jar to the script. The example uses 0.1.2 and needs a JDK and Python 3. Each pair sends two turns on one HTTP/1.1 connection and reads through the final HTTP chunk. On a read timeout the script saves the partial reply, JVM thread dump and class histogram in the temporary directory it prints. [Issue #1](https://github.com/hjosugi/spring-chat/issues/1)

```bash
python3 scripts/check-webflux-sse.py spring-chat-sample-webflux/target/spring-chat-sample-webflux-0.1.2.jar --clients 16 --pairs 125
```

## License

[Apache License 2.0](LICENSE)

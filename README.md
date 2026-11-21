# spring-chat

An AI chat for Spring Boot 4 and Thymeleaf. Add one dependency and a model, and the application has a chat page; one line puts the chat into a page of its own.

- **Spring AI** answers, through the application's `ChatClient`, with any model Spring AI supports.
- **SSE or WebSocket** carries the reply, chosen by a property.
- **Spring MVC or WebFlux**: the same starter configures itself for either.
- **No React and no build step**: the UI is the [Deep Chat](https://deepchat.dev) web component, served from a WebJar.

| spring-chat | Spring Boot | Spring AI | Java |
| --- | --- | --- | --- |
| 0.1 | 4.2 | 2.1 | 21 or later |

## Use it

Add the starter, a web stack and a model:

```xml
<dependency>
    <groupId>io.github.hjosugi</groupId>
    <artifactId>spring-chat-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
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

The starter is not on Maven Central yet: `./mvnw install` this repository first.

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
| Browser sends | `{"conversationId", "text"}` as the request body | `{"type": "message", "conversationId", "text"}`, and `{"type": "stop"}` |
| Decisions | `{"conversationId", "decisions": [{"id", "allow"}]}` as the request body | `{"type": "message", "conversationId", "decisions": [...]}` |
| Stop | closes the request | sends `stop`; the connection stays |

Both receive the same events, as JSON with a `type`: `start`, then `text` with a `delta` as the reply arrives and `ping` every 5 seconds while it is quiet, then one of:

- `done`, with a `reason` if the model did not finish its reply: `truncated`, when it reached the longest reply it may write, or `filtered`, when the model or its provider stopped it.
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

As environment variables they are `SPRINGCHAT_PATH`, `SPRINGCHAT_TRANSPORT` and so on: Spring Boot drops the dash.

The model is configured as Spring AI documents it, for example `spring.ai.anthropic.api-key`.

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

A `ChatService` of your own streams `ChatReply`s from `reply(ChatRequest)` to take part: `ChatReply.Text`, `ChatReply.Confirmation` and `ChatReply.Ended`. `ChatService.stream` is all that a service has to implement otherwise.

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
- **Virtual threads work.** With `spring.threads.virtual.enabled=true` the web server runs on them, and Reactor's `boundedElastic`, where the chat asks the model and sends over WebSocket, runs on them with `-Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=true`. `./mvnw verify -Pvirtual-threads` runs the tests that way. The chat holds no `synchronized` lock while it waits for the network, which on Java 21 would keep a virtual thread on its carrier thread; a tool or a model client of your own may.
- **A reply that was cut says so.** The chat reads the finish reason of the model, and the page tells the user that the reply reached the length limit or was stopped by the model. A reply without a word, which is how Anthropic's model can refuse, shows that the model gave no answer.
- **A conversation has one reply at a time.** Spring AI's session does not know whether a reply is still being written, and two that run at once mix their messages up. A new message ends the reply that is still being written for its conversation, as a stop would, over either transport. Decisions that arrive while a reply is being written are refused.
- **Errors show one fixed sentence.** The cause is in the server log only, because it can name the provider or a key.
- **A stopped reply can go on at the provider.** The chat cancels the reply and the browser sees it stop, but Spring AI 2.0.2 and 2.1.0 do not always close their connection to the provider. Over HTTP/1.1 it stays open with the models of Anthropic, where the log shows `Unbalanced enter/exit`, of OpenAI and of Google: the model writes the rest of the reply, and it is billed. A ceiling on the length of a reply limits what that costs. [spring-chat-sample](https://github.com/hjosugi/spring-chat-sample#stopping-a-reply-does-not-always-end-it-at-the-provider) has the table by provider and the tests that show it.
- **Native images** get the hints the chat needs, but no native build has been tried yet.

## Samples

```bash
./mvnw install
./mvnw -pl spring-chat-sample-webmvc spring-boot:run    # or spring-chat-sample-webflux
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
spring-chat-sample-webmvc/              the chat in a Spring MVC application
spring-chat-sample-webflux/             the same in a WebFlux application
```

Applications use `io.github.hjosugi.springchat` only; the packages below it may change between versions.

`./mvnw verify` runs the tests. The library's start a server on each web stack and talk to it over real SSE and WebSocket connections; the samples' do the same through Spring AI, with the demo model.

## License

[Apache License 2.0](LICENSE)

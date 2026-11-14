# spring-chat

An AI chat for Spring Boot 4 and Thymeleaf. Add one dependency and a model, and the application has a chat page; one line puts the chat into a page of its own.

- **Spring AI** answers, through the application's `ChatClient`, with any model Spring AI supports.
- **SSE or WebSocket** carries the reply, chosen by a property.
- **Spring MVC or WebFlux**: the same starter configures itself for either.
- **No React and no build step**: the UI is the [Deep Chat](https://deepchat.dev) web component, served from a WebJar.

| spring-chat | Spring Boot | Spring AI | Java |
| --- | --- | --- | --- |
| 0.1 | 4.1 | 2.0 | 21 or later |

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
| Stop | closes the request | sends `stop`; the connection stays |

Both receive the same events, as JSON with a `type`: `start`, then `text` with a `delta` as the reply arrives and `ping` every 5 seconds while it is quiet, then `done` or `error` with a `message`.

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
| `spring-chat.max-message-length` | `8000` | Longest accepted message, in characters |

As environment variables they are `SPRINGCHAT_PATH`, `SPRINGCHAT_TRANSPORT` and so on: Spring Boot drops the dash.

The model is configured as Spring AI documents it, for example `spring.ai.anthropic.api-key`.

## Change what answers

The default answer is a call to Spring AI's `ChatClient` with the system prompt and the conversation memory, which is Spring AI's `ChatMemory` bean. A `ChatPromptCustomizer` adds tools, advisors or options to each call and keeps the rest:

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
- **Nothing removes old conversations.** Spring AI's default `ChatMemory` keeps them in memory until the application stops, and every page that sends a message adds one. For an application that runs long, add one of Spring AI's chat memory repository starters and clear it on your own schedule, or set `spring-chat.memory.enabled=false`.
- **Errors show one fixed sentence.** The cause is in the server log only, because it can name the provider or a key.
- **With Spring AI's Anthropic model, a stopped reply goes on at Anthropic.** The chat cancels the reply and the browser sees it stop, but Spring AI 2.0.2 (and 2.1.0-RC2) fails to close its connection while it reads from it; the log shows `Unbalanced enter/exit`. The model writes the rest of the reply, and it is billed. An application can close that connection itself, through a hook Spring AI provides: [spring-chat-sample](https://github.com/hjosugi/spring-chat-sample#a-stopped-reply-ends-at-anthropic-too) does, in one class and one bean. Other models have not been tried.
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

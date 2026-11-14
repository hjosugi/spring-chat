# spring-chat

An AI chat for Spring Boot 4 and Thymeleaf. Add one dependency and a model, and the application has a chat page; one line puts the chat into a page of its own.

- **Spring AI** answers, through the application's `ChatClient`, with any model Spring AI supports.
- **SSE or WebSocket** carries the reply, chosen by a property.
- **Spring MVC or WebFlux**: the same starter configures itself for either.
- **No React and no build step**: the UI is the [Deep Chat](https://deepchat.dev) web component, served from a WebJar.

Needs Java 21 and Spring Boot 4.1. Built with Spring AI 2.0.

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

The starter is not on Maven Central yet: `mvn install` this repository first.

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

On Spring MVC, WebSocket also needs `spring-boot-starter-websocket`; the application fails at startup with that message if it is missing. WebFlux needs nothing more.

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

Declare a `ChatService` to answer with something other than the default `ChatClient` call, for example with tools:

```java
@Bean
ChatService chatService(ChatClient.Builder builder, OrderTools orderTools) {
    ChatClient client = builder.defaultTools(orderTools).build();
    return (request) -> client.prompt().user(request.text()).stream().content();
}
```

The default service adds the system prompt and the conversation memory; a service of your own adds what it wants of them.

Conversation memory is Spring AI's `ChatMemory` bean. Colors are CSS variables (`--spring-chat-primary` and the others at the top of `spring-chat.css`) and follow the system's light or dark scheme.

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

## Samples

```bash
mvn install
mvn -pl spring-chat-sample-webmvc spring-boot:run    # or spring-chat-sample-webflux
```

Open <http://localhost:8080>. The page has links for both transports and for the built-in page.

No API key is needed: without one, a demo model repeats the message. For Claude:

```bash
CHAT_MODEL=anthropic ANTHROPIC_API_KEY=... mvn -pl spring-chat-sample-webmvc spring-boot:run
```

## Layout

```
spring-chat-spring-boot-starter/   the library
  .../springchat/                  ChatService, the events, the properties
  .../springchat/autoconfigure/    the two auto-configurations
  .../springchat/web/              SSE and page controllers and the JSON, shared by MVC and WebFlux
  .../springchat/web/servlet/      WebSocket on Spring MVC
  .../springchat/web/reactive/     WebSocket on WebFlux
  resources/templates/spring-chat/ the fragment and the built-in page
  resources/static/spring-chat/    the script that connects Deep Chat, and the styles
spring-chat-sample-webmvc/         the chat in a Spring MVC application
spring-chat-sample-webflux/        the same in a WebFlux application
```

`mvn verify` runs the tests. The starter's start a server on each web stack and talk to it over real SSE and WebSocket connections; the samples' do the same through Spring AI, with the demo model.

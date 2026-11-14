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

One include can differ from the property: `th:insert="~{spring-chat/chat :: chat(transport='websocket')}"`.

On Spring MVC, WebSocket also needs `spring-boot-starter-websocket`; the application fails at startup with that message if it is missing. WebFlux needs nothing more.

| | SSE | WebSocket |
| --- | --- | --- |
| Endpoint | `POST /chat/stream` | `/chat/ws` |
| Browser sends | `{"conversationId", "text"}` as the request body | `{"type": "message", "conversationId", "text"}`, and `{"type": "stop"}` |
| Stop | closes the request | sends `stop`; the connection stays |

Both receive the same events: `start`, any number of `text` with a `delta`, then `done` or `error` with a `message`.

## Properties

| Property | Default | |
| --- | --- | --- |
| `spring-chat.enabled` | `true` | Whether to configure the chat |
| `spring-chat.path` | `/chat` | The page; SSE is at `/stream` and WebSocket at `/ws` below it |
| `spring-chat.transport` | `sse` | `sse` or `websocket` |
| `spring-chat.page.enabled` | `true` | Whether to serve the built-in page. The fragment works without it |
| `spring-chat.title` | `Chat` | Heading of the built-in page |
| `spring-chat.intro` | | First message shown in an empty chat |
| `spring-chat.placeholder` | `Message` | Placeholder of the input |
| `spring-chat.system-prompt` | | System prompt |
| `spring-chat.memory.enabled` | `true` | Whether replies see the earlier messages of their conversation |
| `spring-chat.max-message-length` | `8000` | Longest accepted message, in characters |

The model is configured as Spring AI documents it, for example `spring.ai.anthropic.api-key`.

## Change what answers

Every bean backs off when the application declares its own. To answer with something other than the default `ChatClient` call (tools, RAG, another client), declare a `ChatService`:

```java
@Bean
ChatService chatService(ChatClient.Builder builder, VectorStore vectorStore) {
    ChatClient client = builder.defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore).build()).build();
    return (request) -> client.prompt().user(request.text()).stream().content();
}
```

Conversation memory is Spring AI's `ChatMemory` bean: in memory by default, and in a database once one of Spring AI's chat memory repository starters is added.

Colors are CSS variables (`--spring-chat-primary` and the others at the top of `spring-chat.css`) and follow the system's light or dark scheme.

## Good to know

- **A conversation lasts as long as the page.** The browser makes up the conversation id on load, so a reload starts a new conversation, and the server keeps no history per user.
- **The endpoints are open.** Anyone who can reach them can spend the model's tokens. Protect `spring-chat.path` and what is below it with Spring Security as any other route; `/webjars/**` and `/spring-chat/**` are static files. With CSRF protection on, the fragment sends the token of the `_csrf` request attribute.
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
  .../springchat/web/              SSE and page controllers, shared by MVC and WebFlux
  .../springchat/web/servlet/      WebSocket on Spring MVC
  .../springchat/web/reactive/     WebSocket on WebFlux
  resources/templates/spring-chat/ the fragment and the built-in page
  resources/static/spring-chat/    the script that connects Deep Chat, and the styles
spring-chat-sample-webmvc/         the chat in a Spring MVC application
spring-chat-sample-webflux/        the same in a WebFlux application
```

`mvn verify` runs the tests: the auto-configuration, and each sample over real SSE and WebSocket connections.

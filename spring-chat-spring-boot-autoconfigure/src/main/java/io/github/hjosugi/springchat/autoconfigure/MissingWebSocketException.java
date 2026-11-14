package io.github.hjosugi.springchat.autoconfigure;

/**
 * Thrown at startup when spring-chat.transport is websocket on Spring MVC without
 * Spring's WebSocket support.
 *
 * @see MissingWebSocketFailureAnalyzer
 */
class MissingWebSocketException extends IllegalStateException {

	MissingWebSocketException() {
		super("spring-chat.transport=websocket needs spring-boot-starter-websocket on the classpath");
	}

}

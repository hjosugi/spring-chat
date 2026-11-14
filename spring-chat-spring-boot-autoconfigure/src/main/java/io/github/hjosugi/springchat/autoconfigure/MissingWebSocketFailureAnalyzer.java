package io.github.hjosugi.springchat.autoconfigure;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Says what to do when the application fails to start with a
 * {@link MissingWebSocketException}, in place of the stack trace.
 */
class MissingWebSocketFailureAnalyzer extends AbstractFailureAnalyzer<MissingWebSocketException> {

	@Override
	protected FailureAnalysis analyze(Throwable rootFailure, MissingWebSocketException cause) {
		return new FailureAnalysis(
				"spring-chat.transport is websocket, but this Spring MVC application has no WebSocket support.",
				"Add spring-boot-starter-websocket to the application, or set spring-chat.transport to sse.", cause);
	}

}

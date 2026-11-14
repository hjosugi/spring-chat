package io.github.hjosugi.springchat;

/**
 * How the browser receives a reply.
 */
public enum ChatTransport {

	/**
	 * Server-sent events, as the response to an HTTP POST.
	 */
	SSE,

	/**
	 * One WebSocket connection for the whole page.
	 */
	WEBSOCKET

}

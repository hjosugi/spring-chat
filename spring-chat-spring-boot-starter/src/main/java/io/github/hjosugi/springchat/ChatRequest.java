package io.github.hjosugi.springchat;

/**
 * One user message.
 *
 * @param conversationId names the conversation whose earlier messages the reply should see
 * @param text the message
 */
public record ChatRequest(String conversationId, String text) {
}

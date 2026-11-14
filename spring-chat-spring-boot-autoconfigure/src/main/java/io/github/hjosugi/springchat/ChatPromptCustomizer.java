package io.github.hjosugi.springchat;

import org.springframework.ai.chat.client.ChatClient;

/**
 * Adds to each prompt of the default {@link ChatService}, which keeps its system prompt
 * and conversation memory: tools, a tool context, advisors or options. Declare beans of
 * this type; they apply in their {@link org.springframework.core.annotation.Order order}.
 * <pre class="code">
 * &#64;Bean
 * ChatPromptCustomizer orderTools(OrderTools tools) {
 *     return (prompt, request) -&gt; prompt.tools(tools);
 * }
 * </pre>
 */
@FunctionalInterface
public interface ChatPromptCustomizer {

	/**
	 * Customizes the prompt for one message.
	 * @param prompt the prompt, with the user's text already set
	 * @param request the message, and who sent it
	 */
	void customize(ChatClient.ChatClientRequestSpec prompt, ChatRequest request);

}

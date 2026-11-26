package io.github.hjosugi.springchat.sample

import java.time.Duration
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux

/**
 * Stands in for a real model so the sample runs without an API key: it repeats the message,
 * word by word, counts the conversation's messages to show the memory, and names the tools
 * that the prompt offers, to show what a customizer added.
 */
@Component
@ConditionalOnProperty(name = ["spring.ai.model.chat"], havingValue = "demo")
class DemoChatModel : ChatModel {

    /** Options that can carry tools: the client puts the tools of a prompt into the options of its model. */
    override fun getOptions(): ChatOptions = ToolCallingChatOptions.builder().build()

    override fun call(prompt: Prompt): ChatResponse = response(reply(prompt))

    override fun stream(prompt: Prompt): Flux<ChatResponse> =
        Flux.fromIterable(WORDS.split(reply(prompt)))
            .delayElements(Duration.ofMillis(40))
            .map(::response)

    private fun reply(prompt: Prompt): String {
        val messages = prompt.instructions.count { it.messageType == MessageType.USER }
        val tools = (prompt.options as? ToolCallingChatOptions)?.toolCallbacks.orEmpty()
            .joinToString { it.toolDefinition.name() }
            .ifEmpty { "none" }
        return "You said: **${prompt.userMessage.text}**\n\n" +
            "That was message $messages of this conversation. Tools offered: $tools. " +
            "Set `ANTHROPIC_API_KEY` for real answers."
    }

    private fun response(text: String) = ChatResponse(listOf(Generation(AssistantMessage(text))))

    private companion object {
        /** Splits after each space, so that the pieces put together are the text. */
        val WORDS = Regex("(?<= )")
    }
}

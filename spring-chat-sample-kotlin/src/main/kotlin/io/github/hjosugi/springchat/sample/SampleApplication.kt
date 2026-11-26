package io.github.hjosugi.springchat.sample

import io.github.hjosugi.springchat.ChatPromptCustomizer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class SampleApplication {

    /**
     * A lambda is a [ChatPromptCustomizer]. This one gives the model a tool, and keeps what the
     * default answer has: the system prompt and the memory of the conversation.
     */
    @Bean
    fun clockTools(): ChatPromptCustomizer {
        val tools = ClockTools()
        return ChatPromptCustomizer { prompt, _ -> prompt.tools(tools) }
    }
}

fun main(args: Array<String>) {
    runApplication<SampleApplication>(*args)
}

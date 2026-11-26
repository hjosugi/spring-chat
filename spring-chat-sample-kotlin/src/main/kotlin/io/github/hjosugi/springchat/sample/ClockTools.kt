package io.github.hjosugi.springchat.sample

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.springframework.ai.tool.annotation.Tool

/**
 * What the model may ask for. The demo model asks for nothing, and says that it was offered it.
 */
class ClockTools {

    @Tool(description = "The current date and time, with the time zone")
    fun now(): String = ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME)
}

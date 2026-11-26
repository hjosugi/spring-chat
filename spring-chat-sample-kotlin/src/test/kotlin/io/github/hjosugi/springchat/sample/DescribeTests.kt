package io.github.hjosugi.springchat.sample

import io.github.hjosugi.springchat.ChatReply
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DescribeTests {

    @Test
    fun aTextIsItsText() {
        assertThat(ChatReply.Text("hello ").describe()).isEqualTo("hello ")
    }

    @Test
    fun aConfirmationNamesTheToolsThatWait() {
        val calls = listOf(
            ChatReply.PendingToolCall("1", "cancelOrder", "{}"),
            ChatReply.PendingToolCall("2", "refund", "{}"),
        )

        assertThat(ChatReply.Confirmation(calls).describe())
            .isEqualTo("\n[waits for you to allow: cancelOrder, refund]")
    }

    @Test
    fun aReplyThatEndedEarlyNamesTheReason() {
        assertThat(ChatReply.Ended(ChatReply.Reason.TRUNCATED).describe()).isEqualTo("\n[ended early: truncated]")
        assertThat(ChatReply.Ended(ChatReply.Reason.FILTERED).describe()).isEqualTo("\n[ended early: filtered]")
    }
}

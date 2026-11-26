/*
 * Kotlin extensions of ChatService. They are in the package of ChatService, so that they
 * need no import.
 */
package io.github.hjosugi.springchat

import java.util.stream.Collectors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle

/**
 * Streams the reply to [request] as pieces of text, in order, as [ChatService.stream] does.
 *
 * The flow is cold: the service is asked when the flow is collected, once for each
 * collection, and not when this function is called. Cancelling the collector cancels the
 * stream of the service, which is how a chat stops a reply the user stopped. An error of
 * the service ends the collection with that error.
 *
 * @param request the message, and who sent it
 * @return the pieces of the reply
 */
public fun ChatService.streamFlow(request: ChatRequest): Flow<String> =
    flow { emitAll(stream(request).asFlow()) }

/**
 * Streams the reply to [request] as [ChatReply]s, in order, as [ChatService.reply] does: the
 * text as it arrives, and a [ChatReply.Confirmation] or a [ChatReply.Ended] where the reply
 * stops. Match them with an exhaustive `when`.
 *
 * The flow is cold, and cancelling and errors work as they do for [streamFlow].
 *
 * @param request the message, or the decisions on a paused reply, and who sent it
 * @return the pieces of the reply
 */
public fun ChatService.replyFlow(request: ChatRequest): Flow<ChatReply> =
    flow { emitAll(reply(request).asFlow()) }

/**
 * Waits for the whole reply to [request] and returns its text: the pieces of
 * [ChatService.stream], joined. A reply without text gives an empty string.
 *
 * The text is all that it returns. A reply that stopped for a tool call that waits for the
 * user's decision, or that ended before the model was done, has only the text that came
 * before; collect [replyFlow] to see how a reply ended. An error of the service is thrown
 * as it is. Cancelling the calling coroutine cancels the stream of the service.
 *
 * @param request the message, and who sent it
 * @return the text of the reply
 */
public suspend fun ChatService.complete(request: ChatRequest): String =
    stream(request).collect(Collectors.joining()).awaitSingle()

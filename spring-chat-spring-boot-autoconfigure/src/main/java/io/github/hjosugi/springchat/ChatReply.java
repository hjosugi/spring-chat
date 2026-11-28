package io.github.hjosugi.springchat;

import java.util.List;
import java.util.Objects;

/**
 * A piece of a reply as the {@link ChatService} streams its text, progress, usage
 * and outcome.
 */
public sealed interface ChatReply {

	/**
	 * The next piece of the reply's text.
	 *
	 * @param delta the text
	 */
	record Text(String delta) implements ChatReply {

		public Text {
			Objects.requireNonNull(delta, "delta");
		}

	}

	/** A piece of the provider's displayable reasoning or reasoning summary. */
	record Reasoning(String delta) implements ChatReply {
		public Reasoning {
			Objects.requireNonNull(delta, "delta");
		}
	}

	/** The model requested this tool. A confirmation may still be needed. */
	record ToolCall(String name) implements ChatReply {
		public ToolCall {
			Objects.requireNonNull(name, "name");
		}
	}

	/** Token counts reported by the model for this reply, including its tool rounds. */
	record Usage(int promptTokens, int completionTokens, int totalTokens) implements ChatReply {
	}

	/**
	 * The reply stops here: the model asked for tool calls that the user has to allow
	 * first, and nothing ran. The browser asks the user and sends the answer as the
	 * {@link ChatRequest#decisions() decisions} of a request of the same conversation.
	 *
	 * @param calls every tool call of the reply, as the model made it
	 */
	record Confirmation(List<PendingToolCall> calls) implements ChatReply {

		public Confirmation {
			calls = List.copyOf(calls);
		}

	}

	/**
	 * The reply ended, but not because the model was done.
	 *
	 * @param reason why
	 */
	record Ended(Reason reason) implements ChatReply {

		public Ended {
			Objects.requireNonNull(reason, "reason");
		}

	}

	/**
	 * A tool call that waits for the user's decision.
	 *
	 * @param id names the call in the decisions
	 * @param name the tool
	 * @param arguments what the model passes it, as JSON. The model makes them up, so a
	 * UI shows them as text and never as markup
	 */
	record PendingToolCall(String id, String name, String arguments) {
	}

	/**
	 * Why a reply ended before the model was done.
	 */
	enum Reason {

		/**
		 * It reached the longest reply the model may write.
		 */
		TRUNCATED,

		/**
		 * The model or its provider stopped it, for a safety rule or a refusal.
		 */
		FILTERED

	}

}

package io.github.hjosugi.springchat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.StreamingParts;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;

/** Observes each model round before the tool advisor consumes its completed calls. */
final class ToolProgressAdvisor implements StreamAdvisor {

	static final String TOOL_PROGRESS = ToolProgressAdvisor.class.getName();

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE - 1;
	}

	@Override
	public String getName() {
		return "Spring Chat tool progress";
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		return Flux.defer(() -> {
			Set<String> announced = new HashSet<>();
			return chain.nextStream(request).concatMapIterable((chunk) -> withProgress(chunk, announced));
		});
	}

	private List<ChatClientResponse> withProgress(ChatClientResponse chunk, Set<String> announced) {
		ChatResponse response = chunk.chatResponse();
		if (response == null || response.getResult() == null) {
			return List.of(chunk);
		}
		int position = 0;
		List<ChatClientResponse> chunks = new ArrayList<>();
		for (MessagePart part : response.getResult().getOutput().getParts()) {
			if (part instanceof ToolCallPart tool) {
				var call = tool.toolCall();
				Integer index = StreamingParts.partIndex(tool);
				if (StringUtils.hasText(call.name()) && firstNotice(announced, call.id(), index, position)) {
					// A metadata-only chunk passes through the tool loop in order, adds no message
					// to the session, and cannot be mistaken for an executable tool call.
					var metadata = ChatResponseMetadata.builder()
						.keyValue(TOOL_PROGRESS, new ChatReply.ToolCall(call.name())).build();
					chunks.add(chunk.mutate().chatResponse(new ChatResponse(List.of(), metadata)).build());
				}
				position++;
			}
		}
		chunks.add(chunk);
		return chunks;
	}

	private static boolean firstNotice(Set<String> announced, @Nullable String id, @Nullable Integer index, int position) {
		// A later chunk may add an id to a call already seen at its block index.
		boolean newId = !StringUtils.hasText(id) || announced.add("id:" + id);
		if (index != null) {
			return announced.add("#" + index) && newId;
		}
		return StringUtils.hasText(id) ? newId : announced.add("position:" + position);
	}

}

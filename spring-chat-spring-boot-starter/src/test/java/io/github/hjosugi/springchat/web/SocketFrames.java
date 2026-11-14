package io.github.hjosugi.springchat.web;

import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The text frames a WebSocket receives, in order.
 */
final class SocketFrames implements WebSocket.Listener {

	private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();

	private final StringBuilder partial = new StringBuilder();

	final CompletableFuture<String> closed = new CompletableFuture<>();

	@Override
	public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
		this.partial.append(data);
		if (last) {
			this.frames.add(this.partial.toString());
			this.partial.setLength(0);
		}
		webSocket.request(1);
		return null;
	}

	@Override
	public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
		this.closed.complete(statusCode + " " + reason);
		return null;
	}

	@Override
	public void onError(WebSocket webSocket, Throwable error) {
		this.closed.completeExceptionally(error);
	}

	/**
	 * The frames up to and including the first that equals {@code last}.
	 */
	List<String> through(String last) throws InterruptedException {
		List<String> received = new ArrayList<>();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		for (;;) {
			String frame = this.frames.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
			if (frame == null) {
				List<String> tail = received.subList(Math.max(0, received.size() - 5), received.size());
				throw new AssertionError("No \"%s\" among %d frames ending with %s; closed: %s".formatted(last,
						received.size(), tail,
						this.closed.isDone() ? this.closed.handle((status, ex) -> status + " " + ex).join() : "no"));
			}
			received.add(frame);
			if (frame.equals(last)) {
				return received;
			}
		}
	}

}

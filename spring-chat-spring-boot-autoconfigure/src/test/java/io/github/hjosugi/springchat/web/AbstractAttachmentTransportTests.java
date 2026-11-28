package io.github.hjosugi.springchat.web;

import java.net.http.WebSocket;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The chat's configured attachment limit exceeds both default WebFlux wire limits. */
abstract class AbstractAttachmentTransportTests extends AbstractTransportTests {

	@Test
	void bothTransportsDeliverAFileOnlyMessageAtTheLimit() throws Exception {
		byte[] bytes = new byte[300 * 1024];
		new Random(1).nextBytes(bytes);
		String body = """
				{"type":"message","conversationId":"photo","attachments":[{"name":"photo.png","mediaType":"image/png","data":"%s"}]}"""
			.formatted(Base64.getEncoder().encodeToString(bytes));
		String expected = text("photo.png image/png " + Arrays.hashCode(bytes));
		assertThat(post(body).body()).contains("data:" + expected, "event:done");
		SocketFrames frames = new SocketFrames();
		WebSocket socket = connect(frames, null);
		try {
			socket.sendText(body, true).join();
			assertThat(frames.through("{\"type\":\"done\"}")).contains(expected);
		}
		finally {
			socket.abort();
		}
	}

	@Test
	void theRequestBodyHasABoundBeforeJsonIsRead() throws Exception {
		assertThat(post(" ".repeat(600 * 1024)).statusCode()).isEqualTo(413);
	}

	@Test
	void theConfiguredLabelsAndAttachmentLimitsReachEachFragment() throws Exception {
		for (String page : new String[] { path(), "/embedded" }) {
			assertThat(send(page).body()).contains("data-allow=\"許可 &amp; 続行\"",
					"data-refused=\"HTTP {status} で拒否されました\"", "data-attachments=\"true\"",
					"data-max-file-size=\"307200\"", "data-max-files=\"1\"");
		}
	}

}

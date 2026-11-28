package io.github.hjosugi.springchat.web;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.main.web-application-type=servlet", "spring-chat.path=/help/assistant",
				"spring-chat.attachments.enabled=true", "spring-chat.attachments.max-count=1",
				"spring-chat.attachments.max-size=300KB", "spring-chat.messages.allow=許可 & 続行",
				"spring-chat.messages.refused=HTTP {status} で拒否されました" })
class ServletCustomPathTests extends AbstractAttachmentTransportTests {

	@Override
	protected String path() {
		return "/help/assistant";
	}

	@Test
	void nothingIsLeftAtTheDefaultPath() throws Exception {
		assertThat(send("/chat").statusCode()).isEqualTo(404);
		assertThat(send("/chat/stream").statusCode()).isEqualTo(404);
	}

}

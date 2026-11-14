package io.github.hjosugi.springchat.web;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

// The application's own JSON naming must not change what the browser and the chat exchange.
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
		"spring.main.web-application-type=servlet", "spring.jackson.property-naming-strategy=SNAKE_CASE" })
class ServletTransportTests extends AbstractTransportTests {

}

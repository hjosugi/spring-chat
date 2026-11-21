package io.github.hjosugi.springchat.autoconfigure;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.github.hjosugi.springchat.ChatClientChatService;
import io.github.hjosugi.springchat.ChatPromptCustomizer;
import io.github.hjosugi.springchat.ChatProperties;
import io.github.hjosugi.springchat.ChatService;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * {@link AutoConfiguration Auto-configuration} for the {@link ChatService}: unless the
 * application declares one, it asks the {@link ChatClient} that Spring AI configures, and
 * keeps each conversation as a session of Spring AI.
 */
@AutoConfiguration(afterName = { "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration",
		"org.springframework.ai.model.session.autoconfigure.SessionServiceAutoConfiguration" })
@ConditionalOnClass(ChatClient.class)
@ConditionalOnBooleanProperty(name = "spring-chat.enabled", matchIfMissing = true)
@EnableConfigurationProperties(ChatProperties.class)
public final class SpringChatAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean(ChatService.class)
	@ConditionalOnBean(ChatClient.Builder.class)
	ChatClientChatService chatService(ChatClient.Builder builder, ObjectProvider<SessionService> sessions,
			ObjectProvider<ChatPromptCustomizer> customizers, ObjectProvider<ToolCallingManager> toolCallingManager,
			ChatProperties properties) {
		if (StringUtils.hasText(properties.systemPrompt())) {
			builder.defaultSystem(properties.systemPrompt());
		}
		SessionService service = properties.memory().enabled() ? sessions.getIfAvailable() : null;
		if (service != null) {
			builder.defaultAdvisors(sessionAdvisors(service, properties));
		}
		// The manager that the client runs its tools with: it knows which tools need the user's confirmation.
		return new ChatClientChatService(builder.build(), customizers.orderedStream().toList(), service,
				toolCallingManager.getIfAvailable(() -> ToolCallingManager.builder().build()));
	}

	private static List<Advisor> sessionAdvisors(
			SessionService service, ChatProperties properties) {
		SessionAdvisors.Builder advisors = SessionAdvisors.builder(service);
		int maxMessages = properties.memory().maxMessages();
		if (maxMessages > 0) {
			advisors.memory((memory) -> memory.eventFilter(EventFilter.lastN(maxMessages)));
		}
		return advisors.build();
	}

	/**
	 * Conversations in memory, for an application that has no session repository of its
	 * own. They are lost when the application stops, and expire after
	 * {@code spring-chat.memory.time-to-live}. For conversations that outlast the
	 * application, add Spring AI's JDBC session starter.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(DefaultSessionService.class)
	@ConditionalOnMissingBean(SessionService.class)
	@ConditionalOnBooleanProperty(name = "spring-chat.memory.enabled", matchIfMissing = true)
	static class InMemorySessionConfiguration {

		@Bean
		SessionService chatSessionService(ChatProperties properties) {
			return DefaultSessionService.builder()
				.sessionRepository(InMemorySessionRepository.builder().build())
				.defaultTimeToLive(properties.memory().timeToLive())
				.build();
		}

		@Bean(destroyMethod = "close")
		ExpiredSessionSweeper chatExpiredSessionSweeper(SessionService chatSessionService, ChatProperties properties) {
			return new ExpiredSessionSweeper(chatSessionService, properties.memory().timeToLive());
		}

	}

	/**
	 * Deletes the sessions that expired. A session service does not, and conversations
	 * that nobody continues would stay in memory until the application stops.
	 */
	static final class ExpiredSessionSweeper implements AutoCloseable {

		private static final Log logger = LogFactory.getLog(ExpiredSessionSweeper.class);

		private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor((task) -> {
			Thread thread = new Thread(task, "spring-chat-sessions");
			thread.setDaemon(true);
			return thread;
		});

		ExpiredSessionSweeper(SessionService sessions, Duration timeToLive) {
			// Often enough that an expired conversation does not stay long, rarely enough to cost nothing.
			long minutes = Math.max(1, Math.min(60, timeToLive.toMinutes() / 10));
			this.executor.scheduleWithFixedDelay(() -> sweep(sessions), minutes, minutes, TimeUnit.MINUTES);
		}

		private static void sweep(SessionService sessions) {
			try {
				int deleted = sessions.deleteExpiredSessions(Instant.now());
				if (deleted > 0) {
					logger.debug("Deleted " + deleted + " expired chat conversations");
				}
			}
			catch (RuntimeException ex) {
				// The next round tries again.
				logger.warn("Could not delete the expired chat conversations", ex);
			}
		}

		@Override
		public void close() {
			this.executor.shutdownNow();
		}

	}

}

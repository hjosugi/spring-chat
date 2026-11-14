package io.github.hjosugi.springchat.autoconfigure;

import io.github.hjosugi.springchat.web.BrowserMessage;
import io.github.hjosugi.springchat.web.ChatEvent;
import io.github.hjosugi.springchat.web.ChatView;
import org.jspecify.annotations.Nullable;

import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * What a native image needs to keep for the chat. The JSON goes through a mapper of the
 * chat's own and the templates read {@link ChatView} by reflection, so Spring finds
 * neither by itself. Boot keeps the templates and static files.
 */
class ChatRuntimeHints implements RuntimeHintsRegistrar {

	@Override
	public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
		new BindingReflectionHintsRegistrar().registerReflectionHints(hints.reflection(), ChatEvent.class,
				BrowserMessage.class, ChatView.class);
	}

}

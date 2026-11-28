package io.github.hjosugi.springchat;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.MimeType;
import org.springframework.util.unit.DataSize;

/**
 * Limits for the files of one user message.
 * @param enabled whether the chat accepts attachments
 * @param maxCount maximum files in a message
 * @param maxSize maximum decoded size of one file
 * @param allowedTypes MIME types or ranges accepted by the chat
 */
@ConfigurationProperties("spring-chat.attachments")
public record AttachmentProperties(@DefaultValue("false") boolean enabled, @DefaultValue("4") int maxCount,
		@DefaultValue("1MB") DataSize maxSize,
		@DefaultValue({ "image/png", "image/jpeg", "application/pdf" }) List<MimeType> allowedTypes) {

	public AttachmentProperties {
		allowedTypes = List.copyOf(allowedTypes);
		if (maxCount < 1 || maxSize.toBytes() < 1 || maxSize.toBytes() >= Integer.MAX_VALUE || allowedTypes.isEmpty()) {
			throw new IllegalArgumentException("Attachment limits must be positive and allowed-types must not be empty");
		}
	}

	/** Upper bound for one JSON body or frame, including base64, escaped text and file names. */
	public int maxRequestBytes(int maxMessageLength) {
		long envelope = Math.max(65536L, 6L * maxMessageLength + 4096);
		long perFile = 4 * ((maxSize.toBytes() + 2) / 3) + 4096;
		if (envelope >= Integer.MAX_VALUE || (enabled && maxCount > (Integer.MAX_VALUE - 1 - envelope) / perFile)) {
			throw new IllegalArgumentException("The configured chat message exceeds the maximum transport size");
		}
		return (int) (envelope + (enabled ? maxCount * perFile : 0));
	}

}

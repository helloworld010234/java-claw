package com.tinyclaw.ports.chatops;

import java.time.Instant;

/**
 * Normalized chat event from any chat platform.
 *
 * <p>Domain-agnostic: no Feishu, Slack, or HTTP concepts.</p>
 *
 * @param eventId   platform event identifier (used for deduplication)
 * @param messageId platform message identifier
 * @param chatId    chat / channel / group identifier
 * @param senderId  sender user identifier
 * @param text      message text content (normalized, trimmed)
 * @param timestamp event timestamp
 * @param type      event type discriminator
 */
public record ChatOpsEvent(
    String eventId,
    String messageId,
    String chatId,
    String senderId,
    String text,
    Instant timestamp,
    Type type
) {

    public enum Type {
        TEXT_MESSAGE,
        URL_VERIFICATION,
        UNKNOWN
    }

    public ChatOpsEvent {
        if (text == null) {
            text = "";
        }
        if (type == null) {
            type = Type.UNKNOWN;
        }
    }

    /**
     * Returns true if this event is a text message with non-blank content.
     */
    public boolean isRunnableTextMessage() {
        return type == Type.TEXT_MESSAGE && text != null && !text.isBlank();
    }

    /**
     * Returns true if this event is a URL verification challenge.
     */
    public boolean isUrlVerification() {
        return type == Type.URL_VERIFICATION;
    }
}

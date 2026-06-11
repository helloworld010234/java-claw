package com.tinyclaw.ports.chatops;

import java.time.Instant;

/**
 * Port for sending outbound messages to a chat channel.
 *
 * <p>Decouples the application and domain layers from any specific chat platform
 * (Feishu, Slack, Discord, etc.). Implementations live in the adapter layer.</p>
 */
public interface ChatOpsMessageSender {

    /**
     * Send a plain text message to the given chat channel.
     *
     * @param chatId  the target chat / channel identifier
     * @param text    the message text; must not contain secrets or full tool arguments
     */
    void sendText(String chatId, String text);

    /**
     * Send a structured status message to the given chat channel.
     *
     * @param chatId  the target chat / channel identifier
     * @param message the structured outbound message
     */
    void sendMessage(String chatId, ChatOpsOutboundMessage message);
}

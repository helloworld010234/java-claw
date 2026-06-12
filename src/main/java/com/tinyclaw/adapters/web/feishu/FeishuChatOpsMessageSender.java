package com.tinyclaw.adapters.web.feishu;

import com.tinyclaw.ports.chatops.ChatOpsMessageSender;
import com.tinyclaw.ports.chatops.ChatOpsOutboundMessage;
import com.tinyclaw.ports.chatops.ChatOpsSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feishu implementation of {@link ChatOpsMessageSender}.
 *
 * <p><strong>Placeholder / skeleton:</strong> this stage wires the port contract
 * but does not perform real Feishu network calls. A real implementation would
 * use the Feishu OpenAPI SDK or a REST client to POST messages to the
 * {@code /open-apis/im/v1/messages} endpoint.</p>
 *
 * <p>When {@code chatops.enabled=false} or the sender is not fully configured,
 * messages are logged and dropped.</p>
 */
public class FeishuChatOpsMessageSender implements ChatOpsMessageSender {

    private static final Logger log = LoggerFactory.getLogger(FeishuChatOpsMessageSender.class);

    private final boolean enabled;

    public FeishuChatOpsMessageSender(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void sendText(String chatId, String text) {
        if (!enabled) {
            log.debug("[FeishuSender] Dropped text message to {} (disabled)", chatId);
            return;
        }
        String safe = ChatOpsSanitizer.sanitize(text);
        log.info("[FeishuSender] Would send to chat {}: {}", chatId, safe);
        // TODO: real Feishu API call
    }

    @Override
    public void sendMessage(String chatId, ChatOpsOutboundMessage message) {
        if (!enabled) {
            log.debug("[FeishuSender] Dropped message to {} (disabled)", chatId);
            return;
        }
        String safe = ChatOpsSanitizer.sanitize(message.text());
        log.info("[FeishuSender] Would send to chat {}: [{}] {}",
            chatId, message.type(), safe);
        // TODO: real Feishu API call with message type formatting
    }
}

package com.tinyclaw.adapters.web.feishu;

import com.tinyclaw.config.ChatOpsProperties;
import com.tinyclaw.ports.chatops.ChatOpsMessageSender;
import com.tinyclaw.ports.chatops.ChatOpsOutboundMessage;
import com.tinyclaw.ports.chatops.ChatOpsSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feishu implementation of {@link ChatOpsMessageSender}.
 *
 * <p>Performs real Feishu OpenAPI calls when ChatOps is enabled and fully
 * configured. It obtains a cached tenant access token, then POSTs text messages
 * to {@code /open-apis/im/v1/messages}.</p>
 *
 * <p>When disabled or misconfigured, messages are logged and dropped without
 * network calls. Errors are caught so the agent run flow is not interrupted.</p>
 */
public class FeishuChatOpsMessageSender implements ChatOpsMessageSender {

    private static final Logger log = LoggerFactory.getLogger(FeishuChatOpsMessageSender.class);

    /**
     * Feishu error codes that indicate the tenant access token is invalid or expired.
     * When one of these is received, the sender invalidates the cached token and
     * retries exactly once.
     */
    private static final int TOKEN_ERROR_CODE_1 = 99991661;
    private static final int TOKEN_ERROR_CODE_2 = 99991663;
    private static final int TOKEN_ERROR_CODE_3 = 99991664;
    private static final int TOKEN_ERROR_CODE_4 = 99991668;

    private static final String TEXT_MESSAGE_TYPE = "TEXT";

    private final ChatOpsProperties properties;
    private final FeishuTenantAccessTokenProvider tokenProvider;
    private final FeishuMessageApiClient messageApiClient;

    /**
     * Creates the sender.
     *
     * @param properties       ChatOps configuration
     * @param tokenProvider    tenant access token provider
     * @param messageApiClient Feishu message API client
     */
    public FeishuChatOpsMessageSender(ChatOpsProperties properties,
                                      FeishuTenantAccessTokenProvider tokenProvider,
                                      FeishuMessageApiClient messageApiClient) {
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }
        if (tokenProvider == null) {
            throw new IllegalArgumentException("tokenProvider must not be null");
        }
        if (messageApiClient == null) {
            throw new IllegalArgumentException("messageApiClient must not be null");
        }
        this.properties = properties;
        this.tokenProvider = tokenProvider;
        this.messageApiClient = messageApiClient;
    }

    @Override
    public void sendText(String chatId, String text) {
        if (!properties.isEnabled()) {
            log.debug("[FeishuSender] Dropped text message to {} (disabled)", chatId);
            return;
        }
        if (!properties.isConfigured()) {
            log.warn("[FeishuSender] ChatOps enabled but not configured (appId/appSecret missing); dropping text message to {}", chatId);
            return;
        }
        String safe = ChatOpsSanitizer.sanitize(text);
        sendInternal(chatId, safe, TEXT_MESSAGE_TYPE);
    }

    @Override
    public void sendMessage(String chatId, ChatOpsOutboundMessage message) {
        if (!properties.isEnabled()) {
            log.debug("[FeishuSender] Dropped message to {} (disabled)", chatId);
            return;
        }
        if (!properties.isConfigured()) {
            log.warn("[FeishuSender] ChatOps enabled but not configured (appId/appSecret missing); dropping message to {}", chatId);
            return;
        }
        String safe = ChatOpsSanitizer.sanitize(message.text());
        String formatted = formatWithPrefix(message.type(), safe);
        String messageType = message.type() != null ? message.type().name() : TEXT_MESSAGE_TYPE;
        sendInternal(chatId, formatted, messageType);
    }

    private void sendInternal(String chatId, String text, String messageType) {
        String token;
        try {
            token = tokenProvider.getToken();
        } catch (Exception e) {
            log.warn("[FeishuSender] Failed to obtain tenant_access_token for chat {}; message will be dropped", chatId, e);
            return;
        }
        trySend(chatId, text, token, false, messageType);
    }

    private void trySend(String chatId, String text, String token, boolean isRetry, String messageType) {
        try {
            String messageId = messageApiClient.sendTextMessage(chatId, text, token);
            log.info("[FeishuSender] Message sent to chat {}, messageId={}, type={}, textLength={}",
                chatId, messageId, messageType, text.length());
        } catch (FeishuApiException e) {
            if (!isRetry && isTokenError(e.getCode())) {
                log.debug("[FeishuSender] Token error detected (code={}), invalidating token and retrying once", e.getCode());
                tokenProvider.invalidate();
                try {
                    String newToken = tokenProvider.getToken();
                    trySend(chatId, text, newToken, true, messageType);
                } catch (Exception retryEx) {
                    log.warn("[FeishuSender] Token refresh failed during retry for chat {}; message dropped", chatId, retryEx);
                }
                return;
            }
            log.warn("[FeishuSender] Failed to send message to chat {}, code={}, msg={}",
                chatId, e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.warn("[FeishuSender] Unexpected error sending message to chat {}", chatId, e);
        }
    }

    private boolean isTokenError(Integer code) {
        if (code == null) {
            return false;
        }
        return code == TOKEN_ERROR_CODE_1
            || code == TOKEN_ERROR_CODE_2
            || code == TOKEN_ERROR_CODE_3
            || code == TOKEN_ERROR_CODE_4;
    }

    private String formatWithPrefix(ChatOpsOutboundMessage.Type type, String text) {
        String prefix = type != null ? "[" + type.name() + "] " : "";
        return prefix + text;
    }
}

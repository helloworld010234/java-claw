package com.tinyclaw.adapters.web.feishu;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.web.feishu.dto.FeishuSendMessageData;
import com.tinyclaw.adapters.web.feishu.dto.FeishuSendMessageRequest;
import com.tinyclaw.adapters.web.feishu.dto.FeishuSendMessageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Feishu IM message API client.
 *
 * <p>Posts text messages to {@code /open-apis/im/v1/messages?receive_id_type=chat_id}.
 * The caller is responsible for providing a valid tenant access token.</p>
 */
public class FeishuMessageApiClient {

    private static final Logger log = LoggerFactory.getLogger(FeishuMessageApiClient.class);

    private static final String SEND_MESSAGE_ENDPOINT = "/open-apis/im/v1/messages?receive_id_type=chat_id";

    private final String baseUrl;
    private final FeishuHttpTransport transport;
    private final ObjectMapper objectMapper;

    /**
     * Creates the client.
     *
     * @param baseUrl      Feishu OpenAPI base URL, e.g. {@code https://open.feishu.cn}
     * @param transport    HTTP transport
     * @param objectMapper JSON mapper
     */
    public FeishuMessageApiClient(String baseUrl, FeishuHttpTransport transport, ObjectMapper objectMapper) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must not be null");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.transport = transport;
        this.objectMapper = objectMapper;
    }

    /**
     * Sends a plain text message to a chat.
     *
     * @param chatId             target chat ID
     * @param text               sanitized message text
     * @param tenantAccessToken  valid tenant access token
     * @return the Feishu message ID returned by the API, or {@code null} if none
     * @throws FeishuApiException if the API returns a non-zero code or the call fails
     */
    public String sendTextMessage(String chatId, String text, String tenantAccessToken) {
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("chatId must not be blank");
        }
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (tenantAccessToken == null || tenantAccessToken.isBlank()) {
            throw new IllegalArgumentException("tenantAccessToken must not be blank");
        }

        String contentJson = serializeContent(text);
        FeishuSendMessageRequest request = new FeishuSendMessageRequest(chatId, "text", contentJson);
        String uri = baseUrl + SEND_MESSAGE_ENDPOINT;

        Map<String, String> headers = Map.of(
            "Authorization", "Bearer " + tenantAccessToken,
            "Content-Type", "application/json"
        );

        log.debug("[FeishuMessageApiClient] Sending text message to chat {}", chatId);
        String responseBody = transport.post(uri, headers, request);
        FeishuSendMessageResponse response = parseResponse(responseBody);

        if (response.code() != 0) {
            throw new FeishuApiException(
                "Failed to send Feishu message: code=" + response.code() + ", msg=" + response.msg(),
                response.code()
            );
        }

        FeishuSendMessageData data = response.data();
        return data != null ? data.messageId() : null;
    }

    private String serializeContent(String text) {
        try {
            return objectMapper.writeValueAsString(Map.of("text", text));
        } catch (JsonProcessingException e) {
            throw new FeishuApiException("Failed to serialize Feishu message content: " + e.getMessage(), e);
        }
    }

    private FeishuSendMessageResponse parseResponse(String body) {
        try {
            return objectMapper.readValue(body, FeishuSendMessageResponse.class);
        } catch (JsonProcessingException e) {
            throw new FeishuApiException("Failed to parse Feishu send-message response: " + e.getMessage(), e);
        }
    }
}

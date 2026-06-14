package com.tinyclaw.adapters.web.feishu.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.ports.chatops.ChatOpsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Parser that converts Feishu webhook payloads into domain-agnostic {@link ChatOpsEvent}s.
 */
public class FeishuEventParser {

    private static final Logger log = LoggerFactory.getLogger(FeishuEventParser.class);
    private final ObjectMapper objectMapper;

    public FeishuEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parse a Feishu webhook payload into a normalized ChatOpsEvent.
     *
     * @param payload the deserialized Feishu payload
     * @return the normalized event; never null
     */
    public ChatOpsEvent parse(FeishuWebhookPayload payload) {
        if (payload == null) {
            return unknownEvent(null, "null payload");
        }

        if (payload.isUrlVerification()) {
            return new ChatOpsEvent(
                payload.uuid(),
                null,
                null,
                null,
                null,
                Instant.now(),
                ChatOpsEvent.Type.URL_VERIFICATION
            );
        }

        if (!payload.isEventCallback()) {
            return unknownEvent(payload.uuid(), "unsupported type: " + payload.type());
        }

        FeishuWebhookPayload.FeishuEvent event = payload.event();
        if (event == null) {
            return unknownEvent(payload.uuid(), "missing event body");
        }

        String eventType = event.type();
        if (!"im.message.receive_v1".equals(eventType)) {
            return unknownEvent(payload.uuid(), "unsupported event type: " + eventType);
        }

        FeishuWebhookPayload.FeishuMessage message = event.message();
        if (message == null) {
            return unknownEvent(payload.uuid(), "missing message");
        }

        String text = extractText(message.content());
        String chatId = message.chat_id();
        String messageId = message.message_id();
        String senderId = extractSenderId(event.sender());

        return new ChatOpsEvent(
            payload.uuid(),
            messageId,
            chatId,
            senderId,
            text,
            Instant.now(),
            ChatOpsEvent.Type.TEXT_MESSAGE
        );
    }

    private String extractSenderId(com.fasterxml.jackson.databind.JsonNode senderNode) {
        if (senderNode == null || senderNode.isNull()) {
            return null;
        }
        if (senderNode.isTextual()) {
            return senderNode.asText();
        }
        // Feishu sender is often an object: {"sender_id":{"union_id":"xxx","user_id":"xxx"},...}
        JsonNode senderIdNode = senderNode.get("sender_id");
        if (senderIdNode != null && senderIdNode.isObject()) {
            JsonNode unionId = senderIdNode.get("union_id");
            if (unionId != null && unionId.isTextual()) {
                return unionId.asText();
            }
            JsonNode userId = senderIdNode.get("user_id");
            if (userId != null && userId.isTextual()) {
                return userId.asText();
            }
        }
        // Fallback: try "user_id" or "union_id" at root of sender object
        JsonNode userId = senderNode.get("user_id");
        if (userId != null && userId.isTextual()) {
            return userId.asText();
        }
        JsonNode unionId = senderNode.get("union_id");
        if (unionId != null && unionId.isTextual()) {
            return unionId.asText();
        }
        // Last resort: return the JSON string representation (truncated)
        String raw = senderNode.toString();
        if (raw.length() > 100) {
            raw = raw.substring(0, 100) + "...";
        }
        return raw;
    }

    private ChatOpsEvent unknownEvent(String eventId, String reason) {
        return new ChatOpsEvent(
            eventId != null ? eventId : "unknown",
            null,
            null,
            null,
            reason,
            Instant.now(),
            ChatOpsEvent.Type.UNKNOWN
        );
    }

    /**
     * Extract plain text from Feishu message content JSON.
     * Feishu text messages wrap content as {"text":"..."}.
     */
    String extractText(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(contentJson);
            JsonNode textNode = root.get("text");
            if (textNode != null && textNode.isTextual()) {
                return textNode.asText().trim();
            }
            // Fallback: if no "text" field, return the whole JSON truncated
            String fallback = contentJson.trim();
            if (fallback.length() > 200) {
                fallback = fallback.substring(0, 200) + "...";
            }
            return fallback;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse Feishu message content; returning truncated fallback");
            String fallback = contentJson.trim();
            if (fallback.length() > 200) {
                fallback = fallback.substring(0, 200) + "...";
            }
            return fallback;
        }
    }
}

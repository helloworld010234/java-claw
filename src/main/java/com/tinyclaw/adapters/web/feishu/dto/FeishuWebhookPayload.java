package com.tinyclaw.adapters.web.feishu.dto;

/**
 * Feishu webhook payload envelope.
 *
 * <p>Adapter-layer DTO: only used for JSON deserialization in the Feishu webhook controller.</p>
 */
public record FeishuWebhookPayload(
    String uuid,
    String token,
    String ts,
    String type,
    FeishuEvent event,
    FeishuChallenge challenge
) {

    public boolean isUrlVerification() {
        return "url_verification".equals(type);
    }

    public boolean isEventCallback() {
        return "event_callback".equals(type);
    }

    public String challengeToken() {
        return challenge != null ? challenge.challenge() : null;
    }

    public record FeishuChallenge(String challenge, String token) {}

    public record FeishuEvent(
        String type,
        String app_id,
        String tenant_key,
        FeishuMessage message,
        String sender,
        String sender_type
    ) {}

    public record FeishuMessage(
        String message_id,
        String root_id,
        String parent_id,
        String create_time,
        String chat_id,
        String chat_type,
        String message_type,
        String content,
        FeishuMentions mentions
    ) {}

    public record FeishuMentions(
        String key,
        String id,
        String id_type,
        String name,
        String tenant_key
    ) {}
}

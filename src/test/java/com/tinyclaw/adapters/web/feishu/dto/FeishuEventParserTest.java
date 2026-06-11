package com.tinyclaw.adapters.web.feishu.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.ports.chatops.ChatOpsEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeishuEventParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FeishuEventParser parser = new FeishuEventParser(objectMapper);

    @Test
    void parseUrlVerification() {
        FeishuWebhookPayload payload = new FeishuWebhookPayload(
            "uuid-1", "tk-1", null, "url_verification",
            null,
            new FeishuWebhookPayload.FeishuChallenge("ch-123", "tk-1")
        );

        ChatOpsEvent event = parser.parse(payload);

        assertThat(event.type()).isEqualTo(ChatOpsEvent.Type.URL_VERIFICATION);
        assertThat(event.eventId()).isEqualTo("uuid-1");
    }

    @Test
    void parseTextMessage() {
        FeishuWebhookPayload payload = buildTextPayload("evt-1", "msg-1", "chat-1", "user-1", "hello world");

        ChatOpsEvent event = parser.parse(payload);

        assertThat(event.type()).isEqualTo(ChatOpsEvent.Type.TEXT_MESSAGE);
        assertThat(event.eventId()).isEqualTo("evt-1");
        assertThat(event.messageId()).isEqualTo("msg-1");
        assertThat(event.chatId()).isEqualTo("chat-1");
        assertThat(event.senderId()).isEqualTo("user-1");
        assertThat(event.text()).isEqualTo("hello world");
    }

    @Test
    void parseUnsupportedEventType() {
        FeishuWebhookPayload payload = new FeishuWebhookPayload(
            "uuid-1", "tk-1", null, "event_callback",
            new FeishuWebhookPayload.FeishuEvent(
                "im.message.read_v1", "app-1", "tenant-1",
                new FeishuWebhookPayload.FeishuMessage(
                    "m-1", null, null, null, "c-1", "group", "text",
                    "{\"text\":\"hello\"}", null
                ),
                "u-1", null
            ),
            null
        );

        ChatOpsEvent event = parser.parse(payload);

        assertThat(event.type()).isEqualTo(ChatOpsEvent.Type.UNKNOWN);
    }

    @Test
    void parseNullPayload() {
        ChatOpsEvent event = parser.parse(null);
        assertThat(event.type()).isEqualTo(ChatOpsEvent.Type.UNKNOWN);
    }

    @Test
    void extractTextFromJson() {
        assertThat(parser.extractText("{\"text\":\"hello\"}")).isEqualTo("hello");
        assertThat(parser.extractText("{\"text\":\"  spaced  \"}")).isEqualTo("spaced");
    }

    @Test
    void extractTextFromInvalidJsonReturnsRawTruncated() {
        String raw = "not json at all but very long ".repeat(20);
        String result = parser.extractText(raw);
        assertThat(result).startsWith("not json");
        assertThat(result).contains("...");
        assertThat(result.length()).isLessThanOrEqualTo(220);
    }

    @Test
    void extractTextFromNullReturnsEmpty() {
        assertThat(parser.extractText(null)).isEqualTo("");
        assertThat(parser.extractText("   ")).isEqualTo("");
    }

    private FeishuWebhookPayload buildTextPayload(String uuid, String msgId, String chatId, String senderId, String text) {
        return new FeishuWebhookPayload(
            uuid, "tk-1", null, "event_callback",
            new FeishuWebhookPayload.FeishuEvent(
                "im.message.receive_v1", "app-1", "tenant-1",
                new FeishuWebhookPayload.FeishuMessage(
                    msgId, null, null, null, chatId, "group", "text",
                    "{\"text\":\"" + text + "\"}", null
                ),
                senderId, null
            ),
            null
        );
    }
}

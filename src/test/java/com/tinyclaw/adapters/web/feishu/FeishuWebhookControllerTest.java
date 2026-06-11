package com.tinyclaw.adapters.web.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.web.feishu.dto.FeishuEventParser;
import com.tinyclaw.adapters.web.feishu.dto.FeishuWebhookPayload;
import com.tinyclaw.application.chatops.ChatOpsEventHandler;
import com.tinyclaw.config.ChatOpsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(FeishuWebhookController.class)
@Import(FeishuEventParser.class)
@TestPropertySource(properties = "tiny-claw.chatops.enabled=true")
class FeishuWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ChatOpsEventHandler eventHandler;

    @MockitoBean
    private ChatOpsProperties chatOpsProperties;

    @Test
    void urlVerificationReturnsChallenge() throws Exception {
        when(chatOpsProperties.isEnabled()).thenReturn(true);

        String body = """
            {
              "uuid": "uuid-1",
              "token": "tk-1",
              "type": "url_verification",
              "challenge": {
                "challenge": "ch-123",
                "token": "tk-1"
              }
            }
            """;

        mockMvc.perform(post("/webhook/feishu/event")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.challenge").value("ch-123"));
    }

    @Test
    void validTextMessageReturnsAccepted() throws Exception {
        when(chatOpsProperties.isEnabled()).thenReturn(true);
        when(eventHandler.handle(any())).thenReturn(true);

        String body = buildTextMessagePayload("evt-1", "msg-1", "chat-1", "user-1", "hello");

        mockMvc.perform(post("/webhook/feishu/event")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("accepted"));
    }

    @Test
    void duplicateEventReturnsDuplicateStatus() throws Exception {
        when(chatOpsProperties.isEnabled()).thenReturn(true);
        when(eventHandler.handle(any())).thenReturn(false);

        String body = buildTextMessagePayload("evt-dup", "msg-1", "chat-1", "user-1", "hello");

        mockMvc.perform(post("/webhook/feishu/event")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("duplicate or rejected"));
    }

    @Test
    void emptyTextMessageReturnsIgnored() throws Exception {
        when(chatOpsProperties.isEnabled()).thenReturn(true);

        String body = buildTextMessagePayload("evt-1", "msg-1", "chat-1", "user-1", "   ");

        mockMvc.perform(post("/webhook/feishu/event")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ignored"));
    }

    @Test
    void unsupportedEventTypeReturnsIgnored() throws Exception {
        when(chatOpsProperties.isEnabled()).thenReturn(true);

        String body = """
            {
              "uuid": "uuid-1",
              "token": "tk-1",
              "type": "event_callback",
              "event": {
                "type": "im.message.read_v1",
                "message": {
                  "message_id": "m-1",
                  "chat_id": "c-1",
                  "content": "{\\"text\\":\\"hello\\"}"
                },
                "sender": "u-1"
              }
            }
            """;

        mockMvc.perform(post("/webhook/feishu/event")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ignored"));
    }

    @Test
    void chatOpsDisabledReturns503() throws Exception {
        when(chatOpsProperties.isEnabled()).thenReturn(false);

        String body = buildTextMessagePayload("evt-1", "msg-1", "chat-1", "user-1", "hello");

        mockMvc.perform(post("/webhook/feishu/event")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("ChatOps disabled"));
    }

    @Test
    void missingChallengeReturnsBadRequest() throws Exception {
        when(chatOpsProperties.isEnabled()).thenReturn(true);

        String body = """
            {
              "uuid": "uuid-1",
              "token": "tk-1",
              "type": "url_verification",
              "challenge": {
                "challenge": "",
                "token": "tk-1"
              }
            }
            """;

        mockMvc.perform(post("/webhook/feishu/event")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Missing challenge"));
    }

    private String buildTextMessagePayload(String uuid, String msgId, String chatId, String senderId, String text) {
        return """
            {
              "uuid": "%s",
              "token": "tk-1",
              "type": "event_callback",
              "event": {
                "type": "im.message.receive_v1",
                "message": {
                  "message_id": "%s",
                  "chat_id": "%s",
                  "message_type": "text",
                  "content": "{\\"text\\":\\"%s\\"}"
                },
                "sender": "%s"
              }
            }
            """.formatted(uuid, msgId, chatId, text, senderId);
    }
}

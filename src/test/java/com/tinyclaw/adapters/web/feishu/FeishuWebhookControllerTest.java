package com.tinyclaw.adapters.web.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.web.feishu.dto.FeishuEventParser;
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

import java.util.List;

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
    void blankVerifyTokenRejectsAllRequests() throws Exception {
        when(chatOpsProperties.isEnabled()).thenReturn(true);
        when(chatOpsProperties.getVerifyToken()).thenReturn("");

        String body = buildTextMessagePayload("evt-1", "msg-1", "chat-1", "user-1", "hello");

        mockMvc.perform(post("/webhook/feishu/event")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Unauthorized"));

        verify(eventHandler, never()).handle(any());
    }

    @Test
    void nullVerifyTokenRejectsAllRequests() throws Exception {
        when(chatOpsProperties.isEnabled()).thenReturn(true);
        when(chatOpsProperties.getVerifyToken()).thenReturn(null);

        String body = buildTextMessagePayload("evt-1", "msg-1", "chat-1", "user-1", "hello");

        mockMvc.perform(post("/webhook/feishu/event")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Unauthorized"));

        verify(eventHandler, never()).handle(any());
    }

    @Test
    void urlVerificationWithBlankTokenRejects() throws Exception {
        when(chatOpsProperties.isEnabled()).thenReturn(true);
        when(chatOpsProperties.getVerifyToken()).thenReturn("");

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
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    void urlVerificationWithMismatchTokenRejects() throws Exception {
        when(chatOpsProperties.isEnabled()).thenReturn(true);
        when(chatOpsProperties.getVerifyToken()).thenReturn("secret-token");

        String body = """
            {
              "uuid": "uuid-1",
              "token": "wrong-token",
              "type": "url_verification",
              "challenge": {
                "challenge": "ch-123",
                "token": "wrong-token"
              }
            }
            """;

        mockMvc.perform(post("/webhook/feishu/event")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    void urlVerificationWithMatchTokenReturnsChallenge() throws Exception {
        when(chatOpsProperties.isEnabled()).thenReturn(true);
        when(chatOpsProperties.getVerifyToken()).thenReturn("tk-1");

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
        when(chatOpsProperties.getVerifyToken()).thenReturn("tk-1");
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
        when(chatOpsProperties.getVerifyToken()).thenReturn("tk-1");
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
        when(chatOpsProperties.getVerifyToken()).thenReturn("tk-1");

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
        when(chatOpsProperties.getVerifyToken()).thenReturn("tk-1");

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
        when(chatOpsProperties.getVerifyToken()).thenReturn("tk-1");

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

    @Test
    void tokenMismatchReturnsUnauthorized() throws Exception {
        when(chatOpsProperties.isEnabled()).thenReturn(true);
        when(chatOpsProperties.getVerifyToken()).thenReturn("secret-token");

        String body = buildTextMessagePayload("evt-1", "msg-1", "chat-1", "user-1", "hello");
        // body uses token "tk-1" which does not match "secret-token"

        mockMvc.perform(post("/webhook/feishu/event")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    void tokenMatchProceeds() throws Exception {
        when(chatOpsProperties.isEnabled()).thenReturn(true);
        when(chatOpsProperties.getVerifyToken()).thenReturn("tk-1");
        when(eventHandler.handle(any())).thenReturn(true);

        String body = buildTextMessagePayload("evt-1", "msg-1", "chat-1", "user-1", "hello");

        mockMvc.perform(post("/webhook/feishu/event")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("accepted"));
    }

    @Test
    void chatNotInAllowlistReturnsForbidden() throws Exception {
        when(chatOpsProperties.isEnabled()).thenReturn(true);
        when(chatOpsProperties.getVerifyToken()).thenReturn("tk-1");
        when(chatOpsProperties.getAllowedChatIds()).thenReturn(List.of("allowed-chat-1"));

        String body = buildTextMessagePayload("evt-1", "msg-1", "unauthorized-chat", "user-1", "hello");

        mockMvc.perform(post("/webhook/feishu/event")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Chat not allowed"));
    }

    @Test
    void chatInAllowlistProceeds() throws Exception {
        when(chatOpsProperties.isEnabled()).thenReturn(true);
        when(chatOpsProperties.getVerifyToken()).thenReturn("tk-1");
        when(chatOpsProperties.getAllowedChatIds()).thenReturn(List.of("allowed-chat-1"));
        when(eventHandler.handle(any())).thenReturn(true);

        String body = buildTextMessagePayload("evt-1", "msg-1", "allowed-chat-1", "user-1", "hello");

        mockMvc.perform(post("/webhook/feishu/event")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("accepted"));
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

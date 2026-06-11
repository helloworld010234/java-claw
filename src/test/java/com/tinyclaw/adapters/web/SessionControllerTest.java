package com.tinyclaw.adapters.web;

import com.tinyclaw.application.persistence.AgentMessageDto;
import com.tinyclaw.domain.message.Role;
import com.tinyclaw.ports.persistence.MessageRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(SessionController.class)
class SessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MessageRepositoryPort messageRepository;

    @Test
    void getSessionMessagesReturnsList() throws Exception {
        List<AgentMessageDto> messages = List.of(
            new AgentMessageDto("1", "run-1", "sess-1", Role.USER, "Hello", null, null, 0, Instant.parse("2024-01-01T00:00:00Z")),
            new AgentMessageDto("2", "run-1", "sess-1", Role.ASSISTANT, "Hi there", null, null, 1, Instant.parse("2024-01-01T00:00:01Z"))
        );
        when(messageRepository.findBySessionId("sess-1", 50)).thenReturn(messages);

        mockMvc.perform(get("/api/v1/sessions/sess-1/messages"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].role").value("USER"))
            .andExpect(jsonPath("$[0].content").value("Hello"))
            .andExpect(jsonPath("$[1].role").value("ASSISTANT"))
            .andExpect(jsonPath("$[1].content").value("Hi there"));
    }

    @Test
    void getSessionMessagesWithLimit() throws Exception {
        List<AgentMessageDto> messages = List.of(
            new AgentMessageDto("1", "run-1", "sess-1", Role.USER, "Hello", null, null, 0, Instant.now())
        );
        when(messageRepository.findBySessionId("sess-1", 10)).thenReturn(messages);

        mockMvc.perform(get("/api/v1/sessions/sess-1/messages?limit=10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void getEmptySessionMessagesReturnsEmptyList() throws Exception {
        when(messageRepository.findBySessionId("sess-empty", 50)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/sessions/sess-empty/messages"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }
}

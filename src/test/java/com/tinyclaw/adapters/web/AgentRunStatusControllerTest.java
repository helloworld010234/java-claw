package com.tinyclaw.adapters.web;

import com.tinyclaw.ports.persistence.AgentRunSummary;
import com.tinyclaw.domain.run.AgentRunStatus;
import com.tinyclaw.ports.persistence.RunRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(AgentRunStatusController.class)
class AgentRunStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RunRepositoryPort runRepository;

    @Test
    void getExistingRunReturnsStatus() throws Exception {
        AgentRunSummary run = new AgentRunSummary(
            "run-1", "sess-1", "api", AgentRunStatus.COMPLETED,
            3, "test prompt", null,
            Instant.parse("2024-01-01T00:00:00Z"),
            Instant.parse("2024-01-01T00:01:00Z"),
            null
        );
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));

        mockMvc.perform(get("/api/v1/runs/run-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runId").value("run-1"))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.turnCount").value(3))
            .andExpect(jsonPath("$.errorReason").doesNotExist())
            .andExpect(jsonPath("$.approvalId").doesNotExist());
    }

    @Test
    void getFailedRunReturnsErrorReason() throws Exception {
        AgentRunSummary run = new AgentRunSummary(
            "run-2", "sess-1", "api", AgentRunStatus.FAILED,
            2, "test prompt", "LLM timeout",
            Instant.parse("2024-01-01T00:00:00Z"),
            Instant.parse("2024-01-01T00:01:00Z"),
            null
        );
        when(runRepository.findById("run-2")).thenReturn(Optional.of(run));

        mockMvc.perform(get("/api/v1/runs/run-2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.errorReason").value("LLM timeout"));
    }

    @Test
    void getNonExistentRunReturnsNotFound() throws Exception {
        when(runRepository.findById("run-999")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/runs/run-999"))
            .andExpect(status().isNotFound());
    }

    @Test
    void getWaitingApprovalRunReturnsApprovalId() throws Exception {
        AgentRunSummary run = new AgentRunSummary(
            "run-3", "sess-1", "api", AgentRunStatus.WAITING_APPROVAL,
            2, "approval smoke", null,
            Instant.parse("2024-01-01T00:00:00Z"),
            null,
            "app-smoke-1"
        );
        when(runRepository.findById("run-3")).thenReturn(Optional.of(run));

        mockMvc.perform(get("/api/v1/runs/run-3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("WAITING_APPROVAL"))
            .andExpect(jsonPath("$.approvalId").value("app-smoke-1"));
    }
}

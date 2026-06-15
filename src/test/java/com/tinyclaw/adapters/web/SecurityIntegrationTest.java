package com.tinyclaw.adapters.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.web.dto.StartRunRequest;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.AgentRunResult;
import com.tinyclaw.ports.persistence.AgentRunSummary;
import com.tinyclaw.application.run.AgentRunExecutionService;
import com.tinyclaw.adapters.workspace.WorkspaceSecurityService;
import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.run.AgentRunStatus;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
import com.tinyclaw.ports.persistence.RunRepositoryPort;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import com.tinyclaw.ports.session.SessionService;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全认证集成测试。
 *
 * <p>验证 API Key 认证、分级权限、Actuator 匿名访问等功能。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "tiny-claw.security.enabled=true",
    "tiny-claw.security.api-key.user-key=test-user-key",
    "tiny-claw.security.api-key.admin-key=test-admin-key",
    "spring.ai.openai.api-key=dummy-key"
})
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RunRepositoryPort runRepository;

    @MockitoBean
    private LlmGateway llmGateway;

    @MockitoBean
    private ApprovalRepositoryPort approvalRepository;

    @MockitoBean
    private AgentRunExecutionService executionService;

    @MockitoBean
    private AgentEngine agentEngine;

    @MockitoBean
    private SessionService sessionService;

    @MockitoBean
    private ToolExecutionRepositoryPort toolExecutionRepository;

    @MockitoBean
    private WorkspaceSecurityService workspaceSecurityService;

    @Test
    void noApiKeyReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/runs/run-1"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void noApiKeyPostRunsReturnsUnauthorized() throws Exception {
        StartRunRequest request = new StartRunRequest(null, "Hello", null, null);
        mockMvc.perform(post("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void userKeyAccessesNormalApi() throws Exception {
        AgentRunSummary run = new AgentRunSummary(
            "run-1", "sess-1", "api", AgentRunStatus.RUNNING,
            1, "test", null, Instant.now(), null, null
        );
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));

        mockMvc.perform(get("/api/v1/runs/run-1")
                .header("X-API-Key", "test-user-key"))
            .andExpect(status().isOk());
    }

    @Test
    void userKeyCanStartRun() throws Exception {
        when(workspaceSecurityService.resolveWorkspace(null)).thenReturn(Path.of("default").toAbsolutePath());
        when(executionService.execute(any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(new AgentRunResult(true, "Done", 1, null));

        StartRunRequest request = new StartRunRequest(null, "Hello", null, null);
        mockMvc.perform(post("/api/v1/runs")
                .header("X-API-Key", "test-user-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isAccepted());
    }

    @Test
    void userKeyCannotAccessApprovalApi() throws Exception {
        mockMvc.perform(post("/api/v1/approvals/app-1/action")
                .header("X-API-Key", "test-user-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"approve\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminKeyAccessesAllApi() throws Exception {
        AgentRunSummary run = new AgentRunSummary(
            "run-1", "sess-1", "api", AgentRunStatus.RUNNING,
            1, "test", null, Instant.now(), null, null
        );
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));

        mockMvc.perform(get("/api/v1/runs/run-1")
                .header("X-API-Key", "test-admin-key"))
            .andExpect(status().isOk());

        ApprovalRequest approval = ApprovalRequest.pending(
            "app-1", "run-1", "sess-1", "tc-1", "shell_command", "ls", Instant.now()
        );
        when(approvalRepository.findById("app-1")).thenReturn(Optional.of(approval));

        mockMvc.perform(post("/api/v1/approvals/app-1/action")
                .header("X-API-Key", "test-admin-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"approve\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void wrongKeyReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/runs/run-1")
                .header("X-API-Key", "wrong-key"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorAnonymousAccess() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk());
    }
}

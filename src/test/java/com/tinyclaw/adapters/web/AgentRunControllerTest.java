package com.tinyclaw.adapters.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.web.dto.StartRunRequest;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.AgentRunResult;
import com.tinyclaw.application.run.AgentRunExecutionService;
import com.tinyclaw.adapters.workspace.WorkspaceSecurityService;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import com.tinyclaw.ports.session.SessionService;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(AgentRunController.class)
class AgentRunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    @MockitoBean
    private java.util.concurrent.ExecutorService agentRunExecutor;

    @BeforeEach
    void setUpExecutor() {
        // Ensure async tasks submitted to the mock executor actually run synchronously
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(agentRunExecutor).execute(any(Runnable.class));
    }

    @Test
    void startRunWithValidRequestReturnsAccepted() throws Exception {
        when(workspaceSecurityService.resolveWorkspace(null)).thenReturn(Path.of("default").toAbsolutePath());
        when(executionService.execute(any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(new AgentRunResult(true, "Done", 3, null));

        StartRunRequest request = new StartRunRequest(null, "Hello world", null, null);

        mockMvc.perform(post("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.runId").isNotEmpty())
            .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void startRunWithBlankPromptReturnsBadRequest() throws Exception {
        StartRunRequest request = new StartRunRequest(null, "   ", null, null);

        mockMvc.perform(post("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void startRunWithNullPromptReturnsBadRequest() throws Exception {
        StartRunRequest request = new StartRunRequest(null, null, null, null);

        mockMvc.perform(post("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void startRunWithCustomSessionIdAndWorkDir() throws Exception {
        Path allowedPath = Path.of("workspaces", "proj-a").toAbsolutePath();
        when(workspaceSecurityService.resolveWorkspace("proj-a")).thenReturn(allowedPath);
        when(executionService.execute(any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(new AgentRunResult(true, "Done", 1, null));

        StartRunRequest request = new StartRunRequest("sess-123", "Test prompt", "proj-a", 10);

        mockMvc.perform(post("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.runId").isNotEmpty());
    }

    @Test
    void startRunWithAbsoluteWorkDirReturnsBadRequest() throws Exception {
        when(workspaceSecurityService.resolveWorkspace("C:\\tmp"))
            .thenThrow(new IllegalArgumentException("Absolute workspace paths are not allowed"));

        StartRunRequest request = new StartRunRequest(null, "Test prompt", "C:\\tmp", null);

        mockMvc.perform(post("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Absolute workspace paths are not allowed"));
    }

    @Test
    void startRunPassesRunIdAndSessionIdInToolExecutionContext() throws Exception {
        when(workspaceSecurityService.resolveWorkspace(null)).thenReturn(Path.of("default").toAbsolutePath());
        when(executionService.execute(any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(new AgentRunResult(true, "Done", 1, null));

        StartRunRequest request = new StartRunRequest(null, "Hello world", null, null);

        mockMvc.perform(post("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isAccepted());

        ArgumentCaptor<ToolExecutionContext> contextCaptor = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(executionService).execute(any(), any(), any(), contextCaptor.capture(), any(), any(), any(), anyInt());

        ToolExecutionContext captured = contextCaptor.getValue();
        assertThat(captured.runId()).isNotNull().isNotBlank();
        assertThat(captured.sessionId()).isNotNull().isNotBlank();
    }

    @Test
    void startRunWithCustomSessionIdPassesCorrectSessionId() throws Exception {
        when(workspaceSecurityService.resolveWorkspace(null)).thenReturn(Path.of("default").toAbsolutePath());
        when(executionService.execute(any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(new AgentRunResult(true, "Done", 1, null));

        StartRunRequest request = new StartRunRequest("custom-sess-123", "Hello world", null, null);

        mockMvc.perform(post("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isAccepted());

        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        ArgumentCaptor<ToolExecutionContext> contextCaptor = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(executionService).execute(any(), sessionCaptor.capture(), any(), contextCaptor.capture(), any(), any(), any(), anyInt());

        assertThat(sessionCaptor.getValue().id()).isEqualTo("custom-sess-123");
        assertThat(contextCaptor.getValue().sessionId()).isEqualTo("custom-sess-123");
    }

    @Test
    void startRunWithNotAllowedWorkDirReturnsBadRequest() throws Exception {
        when(workspaceSecurityService.resolveWorkspace("unauthorized"))
            .thenThrow(new IllegalArgumentException("Workspace id not in allowlist: unauthorized"));

        StartRunRequest request = new StartRunRequest(null, "Test prompt", "unauthorized", null);

        mockMvc.perform(post("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Workspace id not in allowlist: unauthorized"));
    }
}

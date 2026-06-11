package com.tinyclaw.adapters.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.web.dto.StartRunRequest;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.AgentRunResult;
import com.tinyclaw.application.run.AgentRunExecutionService;
import com.tinyclaw.application.workspace.WorkspaceSecurityService;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import com.tinyclaw.ports.session.SessionService;
import com.tinyclaw.ports.tool.ToolExecutionContext;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    void startRunWithDirectoryTraversalReturnsBadRequest() throws Exception {
        when(workspaceSecurityService.resolveWorkspace("../etc"))
            .thenThrow(new IllegalArgumentException("Workspace path contains directory traversal"));

        StartRunRequest request = new StartRunRequest(null, "Test prompt", "../etc", null);

        mockMvc.perform(post("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Workspace path contains directory traversal"));
    }
}

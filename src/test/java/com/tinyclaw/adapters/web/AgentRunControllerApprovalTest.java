package com.tinyclaw.adapters.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.llm.fake.FakeLlmGateway;
import com.tinyclaw.adapters.reporter.NoOpReporter;
import com.tinyclaw.adapters.session.InMemorySessionService;
import com.tinyclaw.adapters.tools.filesystem.WriteFileTool;
import com.tinyclaw.adapters.tools.filesystem.WorkspacePathResolver;
import com.tinyclaw.adapters.web.dto.StartRunRequest;
import com.tinyclaw.application.approval.ApprovalGatePolicy;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.AgentRunResult;
import com.tinyclaw.application.engine.PromptComposer;
import com.tinyclaw.application.run.AgentRunExecutionService;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
import com.tinyclaw.adapters.workspace.WorkspaceSecurityService;
import com.tinyclaw.ports.llm.LlmResponse;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
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
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;

/**
 * Web run 审批链集成测试。
 *
 * <p>验证从 API 启动的 run 在触发需审批工具时，会正确创建 PENDING approval，
 * 且 runId/sessionId/toolCallId/toolName/argumentsPreview 正确绑定。</p>
 */
@WebMvcTest(AgentRunController.class)
@AutoConfigureMockMvc(addFilters = false)
class AgentRunControllerApprovalTest {

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

    private InMemoryApprovalRepository capturedApprovalRepo;

    @BeforeEach
    void setUpExecutor() {
        // Ensure async tasks submitted to the mock executor actually run synchronously
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(agentRunExecutor).execute(any(Runnable.class));
    }

    @Test
    void webRunCreatesPendingApprovalWithCorrectIds() throws Exception {
        when(workspaceSecurityService.resolveWorkspace(null))
            .thenReturn(Path.of("default").toAbsolutePath());

        // Intercept executionService.execute() and run a real engine with ApprovalGatePolicy
        doAnswer(inv -> {
            String runId = inv.getArgument(0);
            Session session = inv.getArgument(1);
            String prompt = inv.getArgument(2);
            ToolExecutionContext context = inv.getArgument(3);

            capturedApprovalRepo = new InMemoryApprovalRepository();
            Clock clock = Clock.systemUTC();
            ApprovalGatePolicy policy = new ApprovalGatePolicy(
                capturedApprovalRepo, List.of("write_file"), clock
            );

            WorkspacePathResolver pathResolver = new WorkspacePathResolver();
            ObjectMapper om = new ObjectMapper();
            ToolRegistry realRegistry = new ToolRegistry(List.of(
                new WriteFileTool(pathResolver, om)
            ), List.of(policy));

            FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
                new LlmResponse("", List.of(
                    ToolCall.of("tc-web-1", "write_file", "{\"path\":\"out.txt\",\"content\":\"data\"}")
                ), null),
                new LlmResponse("done", List.of(), null)
            ));

            AgentEngine realEngine = new AgentEngine(
                fakeLlm, realRegistry, new PromptComposer(),
                new NoOpReporter(),
                new InMemorySessionService()
            );

            AgentRun run = AgentRun.start(runId, session.id(), 5, Instant.now());
            return realEngine.run(run, session, prompt, context);
        }).when(executionService).execute(any(), any(), any(), any(), any(), any(), any(), anyInt());

        StartRunRequest request = new StartRunRequest("sess-web-1", "Write a file", null, null);

        String responseJson = mockMvc.perform(post("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.runId").isNotEmpty())
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andReturn()
            .getResponse()
            .getContentAsString();

        String responseRunId = objectMapper.readTree(responseJson).get("runId").asText();
        assertThat(responseRunId).isNotNull().isNotBlank();

        // Because the executor is synchronous, the async block has finished by now
        assertThat(capturedApprovalRepo).isNotNull();
        List<ApprovalRequest> pending = capturedApprovalRepo.findByStatus(ApprovalStatus.PENDING);
        assertThat(pending).hasSize(1);

        ApprovalRequest approval = pending.get(0);
        assertThat(approval.runId()).isEqualTo(responseRunId);
        assertThat(approval.sessionId()).isEqualTo("sess-web-1");
        assertThat(approval.toolCallId()).isEqualTo("tc-web-1");
        assertThat(approval.toolName()).isEqualTo("write_file");
        assertThat(approval.argumentsPreview()).isNotNull().isNotBlank();
        assertThat(approval.argumentsPreview()).contains("out.txt");
    }

    private static class InMemoryApprovalRepository implements ApprovalRepositoryPort {
        private final List<ApprovalRequest> requests = new ArrayList<>();

        @Override
        public void save(ApprovalRequest request) {
            requests.add(request);
        }

        @Override
        public Optional<ApprovalRequest> findById(String id) {
            return requests.stream().filter(r -> r.id().equals(id)).findFirst();
        }

        @Override
        public Optional<ApprovalRequest> findByRunIdAndToolCallId(String runId, String toolCallId) {
            return requests.stream()
                .filter(r -> r.runId().equals(runId) && r.toolCallId().equals(toolCallId))
                .findFirst();
        }

        @Override
        public List<ApprovalRequest> findByRunId(String runId) {
            return requests.stream().filter(r -> r.runId().equals(runId)).toList();
        }

        @Override
        public List<ApprovalRequest> findByStatus(ApprovalStatus status) {
            return requests.stream().filter(r -> r.status() == status).toList();
        }

        @Override
        public List<ApprovalRequest> findAll() {
            return List.copyOf(requests);
        }

        @Override
        public void update(ApprovalRequest request) {
            requests.removeIf(r -> r.id().equals(request.id()));
            requests.add(request);
        }

        @Override
        public boolean claimForResume(String approvalId, java.time.Instant now) {
            return false;
        }
    }
}

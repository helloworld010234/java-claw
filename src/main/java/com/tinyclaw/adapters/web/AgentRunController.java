package com.tinyclaw.adapters.web;

import com.tinyclaw.adapters.web.dto.StartRunRequest;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.AgentRunResult;
import com.tinyclaw.application.run.AgentRunExecutionService;
import com.tinyclaw.application.workspace.WorkspaceSecurityService;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import com.tinyclaw.ports.session.SessionService;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agent Run 启动控制器。
 *
 * <p>POST /api/v1/runs 启动一个新的 Agent Run。
 * 由于 Agent Run 可能耗时较长（多轮 LLM 调用），使用异步执行：
 * HTTP 层立即返回 202 ACCEPTED + runId，客户端通过轮询状态接口查询进度。</p>
 */
@RestController
@RequestMapping("/api/v1/runs")
public class AgentRunController {

    private static final Logger log = LoggerFactory.getLogger(AgentRunController.class);
    private static final int DEFAULT_MAX_TURNS = 20;

    private final AgentRunExecutionService executionService;
    private final AgentEngine agentEngine;
    private final SessionService sessionService;
    private final ToolExecutionRepositoryPort toolExecutionRepository;
    private final WorkspaceSecurityService workspaceSecurityService;
    private final ExecutorService executor;

    public AgentRunController(AgentRunExecutionService executionService,
                              AgentEngine agentEngine,
                              SessionService sessionService,
                              ToolExecutionRepositoryPort toolExecutionRepository,
                              WorkspaceSecurityService workspaceSecurityService,
                              ExecutorService agentRunExecutor) {
        this.executionService = executionService;
        this.agentEngine = agentEngine;
        this.sessionService = sessionService;
        this.toolExecutionRepository = toolExecutionRepository;
        this.workspaceSecurityService = workspaceSecurityService;
        this.executor = agentRunExecutor;
    }

    /**
     * 启动新的 Agent Run。
     *
     * @param request 启动请求
     * @return 202 ACCEPTED + runId
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> startRun(@Valid @RequestBody StartRunRequest request) {
        String runId = UUID.randomUUID().toString();
        String sessionId = request.sessionId() != null && !request.sessionId().isBlank()
            ? request.sessionId()
            : UUID.randomUUID().toString();

        Path workspace;
        try {
            workspace = workspaceSecurityService.resolveWorkspace(request.workDir());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage()));
        }

        int maxTurns = request.maxTurns() != null ? request.maxTurns() : DEFAULT_MAX_TURNS;

        Session session = Session.create(sessionId, workspace.toString(), Instant.now());

        ToolExecutionContext context = new ToolExecutionContext(workspace);

        CompletableFuture.runAsync(() -> {
            try {
                log.info("[Run {}] Starting async execution", runId);
                AgentRunResult result = executionService.execute(
                    runId, session, request.prompt(), context,
                    agentEngine, "api", toolExecutionRepository, maxTurns
                );
                log.info("[Run {}] Completed: success={}, turns={}", runId, result.success(), result.turnCount());
            } catch (Exception e) {
                log.error("[Run {}] Execution failed: {}", runId, e.getMessage(), e);
            }
        }, executor);

        return ResponseEntity.accepted()
            .body(Map.of("runId", runId, "status", "RUNNING"));
    }
}

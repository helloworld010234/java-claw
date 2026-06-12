package com.tinyclaw.application.benchmark;

import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.AgentRunResult;
import com.tinyclaw.application.run.AgentRunExecutionService;
import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * Executes a {@link BenchmarkCase} through the real agent engine and validates
 * the outcome.
 *
 * <p>Each run creates an isolated workspace directory so cases never pollute
 * the source tree. The agent run goes through {@link AgentRunExecutionService}
 * so session, run, message and tool execution records are persisted normally.</p>
 */
public class BenchmarkRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunner.class);

    private final AgentRunExecutionService runExecutionService;
    private final AgentEngine agentEngine;
    private final int maxTurns;
    private final ToolExecutionRepositoryPort toolExecutionRepository;

    public BenchmarkRunner(AgentRunExecutionService runExecutionService,
                           AgentEngine agentEngine,
                           int maxTurns) {
        this(runExecutionService, agentEngine, maxTurns, null);
    }

    public BenchmarkRunner(AgentRunExecutionService runExecutionService,
                           AgentEngine agentEngine,
                           int maxTurns,
                           ToolExecutionRepositoryPort toolExecutionRepository) {
        this.runExecutionService = DomainGuards.requireNonNull(runExecutionService, "runExecutionService");
        this.agentEngine = DomainGuards.requireNonNull(agentEngine, "agentEngine");
        this.maxTurns = DomainGuards.requirePositive(maxTurns, "maxTurns");
        this.toolExecutionRepository = toolExecutionRepository;
    }

    /**
     * Runs a single benchmark case.
     *
     * @param benchmarkCase the case to execute
     * @param baseWorkspace the parent directory for isolated workspaces
     * @param llmGateway    the LLM gateway that drives the agent
     * @return the benchmark result
     */
    public BenchmarkResult run(BenchmarkCase benchmarkCase, Path baseWorkspace, LlmGateway llmGateway) {
        DomainGuards.requireNonNull(benchmarkCase, "benchmarkCase");
        DomainGuards.requireNonNull(baseWorkspace, "baseWorkspace");
        DomainGuards.requireNonNull(llmGateway, "llmGateway");

        Path workspace = createWorkspace(baseWorkspace, benchmarkCase.id());
        String sessionId = "bench-" + benchmarkCase.id() + "-" + UUID.randomUUID().toString().substring(0, 8);
        String runId = UUID.randomUUID().toString();

        try {
            benchmarkCase.setup().accept(workspace);
        } catch (Exception e) {
            log.warn("Benchmark case {} setup failed: {}", benchmarkCase.id(), e.getMessage());
            return new BenchmarkResult(
                benchmarkCase.id(),
                BenchmarkStatus.FAILED,
                null,
                null,
                workspace,
                0,
                "Setup failed: " + e.getMessage()
            );
        }

        Session session = Session.create(sessionId, workspace.toAbsolutePath().toString(), Instant.now());
        ToolExecutionContext context = new ToolExecutionContext(workspace).withRun(runId, sessionId);

        AgentEngine engine = agentEngine.withLlmGateway(llmGateway);
        AgentRunResult result;
        try {
            result = runExecutionService.execute(
                runId,
                session,
                benchmarkCase.prompt(),
                context,
                engine,
                "benchmark-fake",
                toolExecutionRepository,
                maxTurns
            );
        } catch (Exception e) {
            log.warn("Benchmark case {} execution failed: {}", benchmarkCase.id(), e.getMessage());
            return new BenchmarkResult(
                benchmarkCase.id(),
                BenchmarkStatus.FAILED,
                runId,
                sessionId,
                workspace,
                0,
                "Execution failed: " + e.getMessage()
            );
        }

        if (!result.success()) {
            return new BenchmarkResult(
                benchmarkCase.id(),
                BenchmarkStatus.FAILED,
                runId,
                sessionId,
                workspace,
                result.turnCount(),
                result.errorReason() != null ? result.errorReason() : "Agent run failed"
            );
        }

        try {
            benchmarkCase.validation().accept(workspace);
        } catch (Exception e) {
            log.warn("Benchmark case {} validation failed: {}", benchmarkCase.id(), e.getMessage());
            return new BenchmarkResult(
                benchmarkCase.id(),
                BenchmarkStatus.FAILED,
                runId,
                sessionId,
                workspace,
                result.turnCount(),
                "Validation failed: " + e.getMessage()
            );
        }

        return new BenchmarkResult(
            benchmarkCase.id(),
            BenchmarkStatus.PASSED,
            runId,
            sessionId,
            workspace,
            result.turnCount(),
            null
        );
    }

    private Path createWorkspace(Path baseWorkspace, String caseId) {
        try {
            if (!Files.exists(baseWorkspace)) {
                Files.createDirectories(baseWorkspace);
            }
            Path workspace = baseWorkspace.resolve(caseId + "-" + UUID.randomUUID().toString().substring(0, 8));
            Files.createDirectories(workspace);
            return workspace;
        } catch (IOException e) {
            throw new BenchmarkException("Failed to create workspace under " + baseWorkspace, e);
        }
    }
}

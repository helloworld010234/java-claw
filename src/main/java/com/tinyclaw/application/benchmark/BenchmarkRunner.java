package com.tinyclaw.application.benchmark;

import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.AgentRunResult;
import com.tinyclaw.application.run.AgentRunExecutionService;
import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.message.Usage;
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
    private static final String DEFAULT_ENGINE_TYPE = "benchmark-fake";

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
     * Runs a single benchmark case using the default fake engine type label.
     *
     * @param benchmarkCase the case to execute
     * @param baseWorkspace the parent directory for isolated workspaces
     * @param llmGateway    the LLM gateway that drives the agent
     * @return the benchmark result
     */
    public BenchmarkResult run(BenchmarkCase benchmarkCase, Path baseWorkspace, LlmGateway llmGateway) {
        return run(benchmarkCase, baseWorkspace, llmGateway, DEFAULT_ENGINE_TYPE);
    }

    /**
     * Runs a single benchmark case.
     *
     * @param benchmarkCase the case to execute
     * @param baseWorkspace the parent directory for isolated workspaces
     * @param llmGateway    the LLM gateway that drives the agent
     * @param engineType    the engine type label persisted with the run (e.g. {@code benchmark-fake} or {@code benchmark-real})
     * @return the benchmark result
     */
    public BenchmarkResult run(BenchmarkCase benchmarkCase, Path baseWorkspace, LlmGateway llmGateway, String engineType) {
        DomainGuards.requireNonNull(benchmarkCase, "benchmarkCase");
        DomainGuards.requireNonNull(baseWorkspace, "baseWorkspace");
        DomainGuards.requireNonNull(llmGateway, "llmGateway");
        DomainGuards.requireNonBlank(engineType, "engineType");

        Path workspace = createWorkspace(baseWorkspace, benchmarkCase.id());
        String sessionId = "bench-" + benchmarkCase.id() + "-" + UUID.randomUUID().toString().substring(0, 8);
        String runId = UUID.randomUUID().toString();
        long startedAt = System.currentTimeMillis();

        try {
            benchmarkCase.setup().accept(workspace);
        } catch (Exception e) {
            log.warn("Benchmark case {} setup failed: {}", benchmarkCase.id(), e.getMessage());
            return failedResult(benchmarkCase.id(), workspace, startedAt,
                "Setup failed: " + e.getMessage(), null, null);
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
                engineType,
                toolExecutionRepository,
                maxTurns
            );
        } catch (Exception e) {
            log.warn("Benchmark case {} execution failed: {}", benchmarkCase.id(), e.getMessage());
            return failedResult(benchmarkCase.id(), workspace, startedAt,
                "Execution failed: " + e.getMessage(), runId, sessionId);
        }

        if (!result.success()) {
            return failedResult(benchmarkCase.id(), workspace, startedAt,
                result.errorReason() != null ? result.errorReason() : "Agent run failed",
                runId, sessionId, result.totalUsage());
        }

        String validationOutput;
        try {
            benchmarkCase.validation().accept(workspace);
            validationOutput = "Validation passed";
        } catch (Exception e) {
            log.warn("Benchmark case {} validation failed: {}", benchmarkCase.id(), e.getMessage());
            return failedResult(benchmarkCase.id(), workspace, startedAt,
                "Validation failed: " + e.getMessage(), runId, sessionId, result.totalUsage());
        }

        long durationMillis = System.currentTimeMillis() - startedAt;
        return new BenchmarkResult(
            benchmarkCase.id(),
            BenchmarkStatus.PASSED,
            runId,
            sessionId,
            workspace,
            result.turnCount(),
            null,
            durationMillis,
            result.totalUsage(),
            validationOutput,
            null
        );
    }

    private BenchmarkResult failedResult(String caseId,
                                         Path workspace,
                                         long startedAt,
                                         String errorReason,
                                         String runId,
                                         String sessionId) {
        return failedResult(caseId, workspace, startedAt, errorReason, runId, sessionId, null);
    }

    private BenchmarkResult failedResult(String caseId,
                                         Path workspace,
                                         long startedAt,
                                         String errorReason,
                                         String runId,
                                         String sessionId,
                                         Usage usage) {
        long durationMillis = System.currentTimeMillis() - startedAt;
        return new BenchmarkResult(
            caseId,
            BenchmarkStatus.FAILED,
            runId,
            sessionId,
            workspace,
            0,
            errorReason,
            durationMillis,
            usage,
            null,
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

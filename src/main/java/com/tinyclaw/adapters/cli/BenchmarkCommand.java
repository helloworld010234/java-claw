package com.tinyclaw.adapters.cli;

import com.tinyclaw.adapters.benchmark.BenchmarkFakeLlmFactory;
import com.tinyclaw.application.benchmark.BenchmarkCase;
import com.tinyclaw.application.benchmark.BenchmarkResult;
import com.tinyclaw.application.benchmark.BenchmarkRunner;
import com.tinyclaw.application.benchmark.BenchmarkStatus;
import com.tinyclaw.application.benchmark.BenchmarkSuite;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.run.AgentRunExecutionService;
import com.tinyclaw.application.tool.AllowAllPolicy;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.config.AgentProperties;
import com.tinyclaw.config.TinyClawModelProperties;
import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmResponse;
import com.tinyclaw.ports.observability.AgentMetricsPort;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import com.tinyclaw.ports.tool.AgentTool;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Picocli command that runs the Java-Claw benchmark suite.
 *
 * <p>By default it uses the fake LLM driver so no API key is required. Each case
 * runs in an isolated workspace under {@code target/benchmark-workspaces}.</p>
 */
@Component
@Scope("prototype")
@CommandLine.Command(
    name = "bench",
    description = "Run the Java-Claw benchmark suite against fake or real LLM engines",
    mixinStandardHelpOptions = true
)
public class BenchmarkCommand implements Callable<Integer> {

    private final AgentRunExecutionService runExecutionService;
    private final AgentEngine agentEngine;
    private final AgentProperties agentProperties;
    private final TinyClawModelProperties modelProperties;
    private final ToolExecutionRepositoryPort toolExecutionRepository;
    private final List<AgentTool> tools;
    private final AgentMetricsPort agentMetrics;

    public BenchmarkCommand(AgentRunExecutionService runExecutionService,
                            AgentEngine agentEngine,
                            AgentProperties agentProperties,
                            TinyClawModelProperties modelProperties,
                            ToolExecutionRepositoryPort toolExecutionRepository,
                            List<AgentTool> tools,
                            AgentMetricsPort agentMetrics) {
        this.runExecutionService = DomainGuards.requireNonNull(runExecutionService, "runExecutionService");
        this.agentEngine = DomainGuards.requireNonNull(agentEngine, "agentEngine");
        this.agentProperties = agentProperties != null ? agentProperties : new AgentProperties();
        this.modelProperties = modelProperties != null ? modelProperties : new TinyClawModelProperties();
        this.toolExecutionRepository = toolExecutionRepository;
        this.tools = DomainGuards.requireNonNull(tools, "tools");
        this.agentMetrics = agentMetrics;
    }

    @CommandLine.Option(names = {"--engine"}, description = "Engine: fake (default) or real")
    private String engine = "fake";

    @CommandLine.Option(names = {"--workspace-root"}, description = "Parent directory for isolated benchmark workspaces")
    private String workspaceRoot = "target/benchmark-workspaces";

    @CommandLine.Option(names = {"--case"}, description = "Run a single case by id (default: all cases)")
    private String caseId;

    @CommandLine.Option(names = {"--go-test"}, description = "Also run 'go test' for Go benchmark cases if Go is installed")
    private boolean goTest;

    @Override
    public Integer call() {
        if (engine != null && !engine.isBlank()
            && !"fake".equalsIgnoreCase(engine)
            && !"real".equalsIgnoreCase(engine)) {
            System.err.println("Invalid engine: " + engine);
            return 2;
        }

        if ("real".equalsIgnoreCase(engine)) {
            if (!modelProperties.isEnabled()) {
                System.err.println("Real LLM engine is not enabled. To use --engine real, set:");
                System.err.println("  tiny-claw.model.enabled=true");
                System.err.println("  tiny-claw.model.api-key=<your-api-key>");
                return 2;
            }
            if (modelProperties.getApiKey() == null || modelProperties.getApiKey().isBlank()) {
                System.err.println("Real LLM engine requires an API key.");
                return 2;
            }
        }

        List<BenchmarkCase> cases = selectCases();
        if (cases.isEmpty()) {
            System.err.println("No benchmark case found for id: " + caseId);
            return 2;
        }

        Path baseWorkspace = Paths.get(workspaceRoot).toAbsolutePath().normalize();
        ToolRegistry benchmarkRegistry = new ToolRegistry(tools, List.of(new AllowAllPolicy()), agentMetrics);
        AgentEngine benchmarkEngine = agentEngine.withToolRegistry(benchmarkRegistry);
        BenchmarkRunner runner = new BenchmarkRunner(
            runExecutionService, benchmarkEngine, agentProperties.getMaxTurns(), toolExecutionRepository
        );

        List<BenchmarkResult> results = new ArrayList<>();
        boolean anyFailed = false;
        for (BenchmarkCase benchmarkCase : cases) {
            LlmGateway llmGateway = createLlmGateway(benchmarkCase);
            BenchmarkResult result = runner.run(benchmarkCase, baseWorkspace, llmGateway);

            if (goTest && result.passed() && BenchmarkSuite.WRITE_TEST_CASE_ID.equals(result.caseId())) {
                result = runGoTestIfAvailable(result);
            }

            results.add(result);
            if (!result.passed()) {
                anyFailed = true;
            }
            printResult(result);
        }

        printSummary(results, anyFailed);
        return anyFailed ? 1 : 0;
    }

    private List<BenchmarkCase> selectCases() {
        List<BenchmarkCase> all = BenchmarkSuite.allCases();
        if (caseId == null || caseId.isBlank()) {
            return BenchmarkSuite.defaultCases();
        }
        return all.stream()
            .filter(c -> c.id().equalsIgnoreCase(caseId))
            .toList();
    }

    private LlmGateway createLlmGateway(BenchmarkCase benchmarkCase) {
        if ("fake".equalsIgnoreCase(engine)) {
            return BenchmarkFakeLlmFactory.forCase(benchmarkCase);
        }
        // Real engine: reuse the default LLM bean behavior by delegating through
        // a simple gateway. This path requires a real API key.
        return request -> new LlmResponse(
            "Real engine benchmark is not yet fully supported; use --engine fake.", List.of(), null
        );
    }

    private BenchmarkResult runGoTestIfAvailable(BenchmarkResult result) {
        try {
            ProcessBuilder pb = new ProcessBuilder("go", "test", "./...");
            pb.directory(result.workspace().toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new BenchmarkResult(
                    result.caseId(),
                    BenchmarkStatus.FAILED,
                    result.runId(),
                    result.sessionId(),
                    result.workspace(),
                    result.turnCount(),
                    "go test timed out"
                );
            }
            if (process.exitValue() != 0) {
                return new BenchmarkResult(
                    result.caseId(),
                    BenchmarkStatus.FAILED,
                    result.runId(),
                    result.sessionId(),
                    result.workspace(),
                    result.turnCount(),
                    "go test failed with exit code " + process.exitValue()
                );
            }
            return result;
        } catch (Exception e) {
            // Go is not installed or not on PATH: treat as a non-fatal skip
            // but keep the original result passed.
            return result;
        }
    }

    private void printResult(BenchmarkResult result) {
        System.out.println("- case: " + result.caseId());
        System.out.println("  status: " + (result.passed() ? "PASSED" : "FAILED"));
        System.out.println("  runId: " + result.runId());
        System.out.println("  sessionId: " + result.sessionId());
        System.out.println("  workspace: " + result.workspace().toAbsolutePath());
        System.out.println("  turns: " + result.turnCount());
        if (result.errorReason() != null) {
            System.out.println("  error: " + result.errorReason());
        }
    }

    private void printSummary(List<BenchmarkResult> results, boolean anyFailed) {
        long passed = results.stream().filter(BenchmarkResult::passed).count();
        System.out.println("summary:");
        System.out.println("  total: " + results.size());
        System.out.println("  passed: " + passed);
        System.out.println("  failed: " + (results.size() - passed));
        System.out.println("  overall: " + (anyFailed ? "FAILED" : "PASSED"));
    }
}

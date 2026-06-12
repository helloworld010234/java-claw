package com.tinyclaw.adapters.cli;

import com.tinyclaw.adapters.benchmark.BenchmarkFakeLlmFactory;
import com.tinyclaw.application.benchmark.BenchmarkCase;
import com.tinyclaw.application.benchmark.BenchmarkResult;
import com.tinyclaw.application.benchmark.BenchmarkRunner;
import com.tinyclaw.application.benchmark.BenchmarkStatus;
import com.tinyclaw.application.benchmark.BenchmarkSuite;
import com.tinyclaw.application.benchmark.GoTestExecutor;
import com.tinyclaw.application.benchmark.GoTestResult;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.run.AgentRunExecutionService;
import com.tinyclaw.application.tool.AllowAllPolicy;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.config.AgentProperties;
import com.tinyclaw.config.TinyClawModelProperties;
import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.ports.llm.LlmGateway;
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
import java.util.Optional;
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

    private static final String FAKE_ENGINE_TYPE = "benchmark-fake";
    private static final String REAL_ENGINE_TYPE = "benchmark-real";

    private final AgentRunExecutionService runExecutionService;
    private final AgentEngine agentEngine;
    private final AgentProperties agentProperties;
    private final TinyClawModelProperties modelProperties;
    private final ToolExecutionRepositoryPort toolExecutionRepository;
    private final List<AgentTool> tools;
    private final AgentMetricsPort agentMetrics;
    private final Optional<LlmGateway> realLlmGateway;

    public BenchmarkCommand(AgentRunExecutionService runExecutionService,
                            AgentEngine agentEngine,
                            AgentProperties agentProperties,
                            TinyClawModelProperties modelProperties,
                            ToolExecutionRepositoryPort toolExecutionRepository,
                            List<AgentTool> tools,
                            AgentMetricsPort agentMetrics) {
        this(runExecutionService, agentEngine, agentProperties, modelProperties,
            toolExecutionRepository, tools, agentMetrics, Optional.empty());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BenchmarkCommand(AgentRunExecutionService runExecutionService,
                            AgentEngine agentEngine,
                            AgentProperties agentProperties,
                            TinyClawModelProperties modelProperties,
                            ToolExecutionRepositoryPort toolExecutionRepository,
                            List<AgentTool> tools,
                            AgentMetricsPort agentMetrics,
                            Optional<LlmGateway> realLlmGateway) {
        this.runExecutionService = DomainGuards.requireNonNull(runExecutionService, "runExecutionService");
        this.agentEngine = DomainGuards.requireNonNull(agentEngine, "agentEngine");
        this.agentProperties = agentProperties != null ? agentProperties : new AgentProperties();
        this.modelProperties = modelProperties != null ? modelProperties : new TinyClawModelProperties();
        this.toolExecutionRepository = toolExecutionRepository;
        this.tools = DomainGuards.requireNonNull(tools, "tools");
        this.agentMetrics = agentMetrics;
        this.realLlmGateway = realLlmGateway != null ? realLlmGateway : Optional.empty();
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

        boolean realMode = "real".equalsIgnoreCase(engine);
        if (realMode) {
            if (!modelProperties.isEnabled()) {
                System.err.println("Real LLM engine is not enabled. To use --engine real, set:");
                System.err.println("  tiny-claw.model.enabled=true");
                System.err.println("  tiny-claw.model.api-key=<your-api-key>");
                return 2;
            }
            if (modelProperties.getApiKey() == null || modelProperties.getApiKey().isBlank()) {
                System.err.println("Real LLM engine requires an API key.");
                System.err.println("Set tiny-claw.model.api-key or the LLM_API_KEY environment variable.");
                return 2;
            }
            if (realLlmGateway.isEmpty()) {
                System.err.println("Real LLM gateway is not available.");
                System.err.println("Ensure tiny-claw.model.enabled=true and a supported provider is configured.");
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
        GoTestExecutor goTestExecutor = new GoTestExecutor();

        String engineType = realMode ? REAL_ENGINE_TYPE : FAKE_ENGINE_TYPE;
        LlmGateway llmGateway = realMode ? realLlmGateway.get() : null;

        List<BenchmarkResult> results = new ArrayList<>();
        boolean anyFailed = false;
        for (BenchmarkCase benchmarkCase : cases) {
            LlmGateway caseGateway = realMode ? llmGateway : BenchmarkFakeLlmFactory.forCase(benchmarkCase);
            BenchmarkResult result = runner.run(benchmarkCase, baseWorkspace, caseGateway, engineType);

            if (goTest && result.passed() && BenchmarkSuite.WRITE_TEST_CASE_ID.equals(result.caseId())) {
                result = runGoTestIfAvailable(result, goTestExecutor);
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

    private BenchmarkResult runGoTestIfAvailable(BenchmarkResult result, GoTestExecutor executor) {
        GoTestResult goResult = executor.runGoTest(result.workspace());
        if (goResult.skipped()) {
            return new BenchmarkResult(
                result.caseId(),
                result.status(),
                result.runId(),
                result.sessionId(),
                result.workspace(),
                result.turnCount(),
                result.errorReason(),
                result.durationMillis(),
                result.usage(),
                result.validationOutput(),
                "Skipped: " + goResult.reason()
            );
        }
        if (!goResult.passed()) {
            return new BenchmarkResult(
                result.caseId(),
                BenchmarkStatus.FAILED,
                result.runId(),
                result.sessionId(),
                result.workspace(),
                result.turnCount(),
                goResult.reason(),
                result.durationMillis(),
                result.usage(),
                result.validationOutput(),
                goResult.output()
            );
        }
        return new BenchmarkResult(
            result.caseId(),
            result.status(),
            result.runId(),
            result.sessionId(),
            result.workspace(),
            result.turnCount(),
            result.errorReason(),
            result.durationMillis(),
            result.usage(),
            result.validationOutput(),
            goResult.output()
        );
    }

    private void printResult(BenchmarkResult result) {
        System.out.println("- case: " + result.caseId());
        System.out.println("  status: " + (result.passed() ? "PASSED" : "FAILED"));
        System.out.println("  runId: " + result.runId());
        System.out.println("  sessionId: " + result.sessionId());
        System.out.println("  workspace: " + result.workspace().toAbsolutePath());
        System.out.println("  turns: " + result.turnCount());
        if (result.durationMillis() != null) {
            System.out.println("  durationMs: " + result.durationMillis());
        }
        if (result.errorReason() != null) {
            System.out.println("  error: " + result.errorReason());
        }
        if (result.goTestOutput() != null) {
            System.out.println("  goTestOutput: |");
            for (String line : result.goTestOutput().split("\r?\n", 6)) {
                System.out.println("    " + line);
            }
            if (result.goTestOutput().split("\r?\n").length > 5) {
                System.out.println("    ...");
            }
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

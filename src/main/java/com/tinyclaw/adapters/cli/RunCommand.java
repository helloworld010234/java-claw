package com.tinyclaw.adapters.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.llm.fake.FakeLlmGateway;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.AgentRunResult;
import com.tinyclaw.application.run.AgentRunExecutionService;
import com.tinyclaw.application.run.ScriptedRunExecutor;
import com.tinyclaw.application.run.ScriptedRunPlan;
import com.tinyclaw.application.run.ScriptedRunResult;
import com.tinyclaw.application.run.ScriptedRunStepResult;
import com.tinyclaw.config.AgentProperties;
import com.tinyclaw.config.TinyClawModelProperties;
import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.message.Usage;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.persistence.RunRepositoryPort;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

@Component
@Scope("prototype")
@Command(
    name = "run",
    description = "Run a single agent task with the given prompt and workspace",
    mixinStandardHelpOptions = true
)
public class RunCommand implements Callable<Integer> {

    private final AgentRunExecutionService runExecutionService;
    private final ScriptedRunExecutor scriptedRunExecutor;
    private final AgentEngine agentEngine;
    private final ObjectMapper objectMapper;
    private final RunRepositoryPort runRepository;
    private final ToolExecutionRepositoryPort toolExecutionRepository;
    private final TinyClawModelProperties modelProperties;
    private final AgentProperties agentProperties;

    public RunCommand(AgentRunExecutionService runExecutionService,
                      ScriptedRunExecutor scriptedRunExecutor,
                      AgentEngine agentEngine,
                      ObjectMapper objectMapper,
                      RunRepositoryPort runRepository,
                      ToolExecutionRepositoryPort toolExecutionRepository,
                      TinyClawModelProperties modelProperties,
                      AgentProperties agentProperties) {
        this.runExecutionService = DomainGuards.requireNonNull(runExecutionService, "runExecutionService");
        this.scriptedRunExecutor = DomainGuards.requireNonNull(scriptedRunExecutor, "scriptedRunExecutor");
        this.agentEngine = DomainGuards.requireNonNull(agentEngine, "agentEngine");
        this.objectMapper = DomainGuards.requireNonNull(objectMapper, "objectMapper");
        this.runRepository = runRepository;
        this.toolExecutionRepository = toolExecutionRepository;
        this.modelProperties = modelProperties != null ? modelProperties : new TinyClawModelProperties();
        this.agentProperties = agentProperties != null ? agentProperties : new AgentProperties();
    }

    @Option(names = {"--prompt"}, required = true, description = "User prompt for the agent task")
    private String prompt;

    @Option(names = {"--dir"}, description = "Workspace directory (default: current directory)")
    private String dir;

    @Option(names = {"--session"}, description = "Session ID (default: auto-generated UUID)")
    private String sessionId;

    @Option(names = {"--plan-file"}, description = "Path to a JSON plan file describing tool steps to execute")
    private String planFile;

    @Option(names = {"--engine"}, description = "Execution engine: none (default), fake, real")
    private String engine;

    @Override
    public Integer call() {
        String effectiveSessionId;
        if (sessionId != null && !sessionId.isBlank()) {
            String trimmed = sessionId.trim();
            if (trimmed.length() > 36) {
                System.err.println("Session ID must not exceed 36 characters, got " + trimmed.length());
                return 2;
            }
            effectiveSessionId = trimmed;
        } else {
            effectiveSessionId = UUID.randomUUID().toString();
        }

        Path workspace;
        try {
            workspace = resolveWorkspace(dir);
        } catch (CommandLine.ParameterException e) {
            System.err.println(e.getMessage());
            return 2;
        }

        if (engine != null && !engine.isBlank()
            && !"fake".equalsIgnoreCase(engine)
            && !"none".equalsIgnoreCase(engine)
            && !"real".equalsIgnoreCase(engine)) {
            System.err.println("Invalid engine: " + engine);
            return 2;
        }

        if (planFile != null && !planFile.isBlank()) {
            return runPlanFile(effectiveSessionId, workspace);
        }

        if ("real".equalsIgnoreCase(engine)) {
            return runWithEngine(effectiveSessionId, workspace, "real");
        }

        if ("fake".equalsIgnoreCase(engine)) {
            return runWithEngine(effectiveSessionId, workspace, "fake");
        }

        System.out.println("sessionId: " + effectiveSessionId);
        System.out.println("workspace: " + workspace.toAbsolutePath());
        System.out.println("prompt: " + prompt);
        return 0;
    }

    private Integer runPlanFile(String effectiveSessionId, Path workspace) {
        Path planPath = Paths.get(planFile).toAbsolutePath().normalize();
        if (!Files.exists(planPath)) {
            System.err.println("Plan file does not exist: " + planFile);
            return 2;
        }
        if (Files.isDirectory(planPath)) {
            System.err.println("Plan file is a directory: " + planFile);
            return 2;
        }

        ScriptedRunPlan plan;
        try {
            plan = objectMapper.readValue(planPath.toFile(), ScriptedRunPlan.class);
        } catch (IOException e) {
            System.err.println("Failed to parse plan file: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            System.err.println("Invalid plan: " + e.getMessage());
            return 2;
        }

        String runId = newRunId();
        Session session = Session.create(effectiveSessionId, workspace.toAbsolutePath().toString(), Instant.now());
        AgentRun run = AgentRun.start(runId, effectiveSessionId, agentProperties.getMaxTurns(), Instant.now());

        if (runRepository != null) {
            runRepository.saveSession(session);
            runRepository.saveRunStarted(run, "plan", prompt);
        }

        ToolExecutionContext context = new ToolExecutionContext(workspace).withRun(run.id(), session.id());
        ScriptedRunResult result = scriptedRunExecutor.execute(plan, context, run.id(), session.id(), toolExecutionRepository);

        if (result.success()) {
            if (runRepository != null) {
                runRepository.saveRunCompleted(run.id(), 0, Instant.now());
            }
        } else {
            String errorReason = result.steps().stream()
                .filter(ScriptedRunStepResult::error)
                .findFirst()
                .map(ScriptedRunStepResult::output)
                .orElse("Plan execution failed");
            if (runRepository != null) {
                runRepository.saveRunFailed(run.id(), 0, errorReason, Instant.now());
            }
        }

        printPlanSummary(runId, effectiveSessionId, workspace, result);
        return result.success() ? 0 : 1;
    }

    private Integer runWithEngine(String effectiveSessionId, Path workspace, String engineType) {
        if ("real".equalsIgnoreCase(engineType)) {
            if (!modelProperties.isEnabled()) {
                System.err.println("Real LLM engine is not enabled. To use --engine real, set the following:");
                System.err.println("  tiny-claw.model.enabled=true");
                System.err.println("  tiny-claw.model.api-key=<your-api-key>");
                System.err.println("  tiny-claw.model.base-url=<optional-base-url>");
                return 2;
            }
            if (modelProperties.getApiKey() == null || modelProperties.getApiKey().isBlank()) {
                System.err.println("Real LLM engine requires an API key. Set tiny-claw.model.api-key or LLM_API_KEY environment variable.");
                return 2;
            }
        }

        Session session;
        if (runRepository != null) {
            Instant now = Instant.now();
            Optional<Session> existing = runRepository.findSessionById(effectiveSessionId);
            session = existing.map(stored -> {
                Instant updatedAt = now.isBefore(stored.createdAt()) ? stored.createdAt() : now;
                return Session.reconstruct(
                    stored.id(),
                    workspace.toAbsolutePath().toString(),
                    stored.status(),
                    stored.createdAt(),
                    updatedAt
                );
            })
                .orElseGet(() -> Session.create(effectiveSessionId, workspace.toAbsolutePath().toString(), now));
        } else {
            session = Session.create(effectiveSessionId, workspace.toAbsolutePath().toString(), Instant.now());
        }

        String runId = newRunId();
        ToolExecutionContext context = new ToolExecutionContext(workspace).withRun(runId, session.id());

        AgentEngine engine;
        if ("fake".equalsIgnoreCase(engineType)) {
            FakeLlmGateway fakeLlm = FakeLlmGateway.forPrompt(prompt);
            engine = agentEngine.withLlmGateway(fakeLlm);
        } else {
            engine = agentEngine.withModelName(modelProperties.getName());
        }

        AgentRunResult result = runExecutionService.execute(
            runId, session, prompt, context, engine, engineType, toolExecutionRepository, agentProperties.getMaxTurns()
        );

        printAgentSummary(runId, effectiveSessionId, workspace, result);
        return result.success() ? 0 : 1;
    }

    private String newRunId() {
        return UUID.randomUUID().toString();
    }

    private Path resolveWorkspace(String dir) {
        if (dir == null || dir.isBlank()) {
            return Paths.get("").toAbsolutePath().normalize();
        }
        Path path = Paths.get(dir).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw new CommandLine.ParameterException(
                new CommandLine(this), "Directory does not exist: " + dir
            );
        }
        if (!Files.isDirectory(path)) {
            throw new CommandLine.ParameterException(
                new CommandLine(this), "Path is not a directory: " + dir
            );
        }
        return path;
    }

    private void printPlanSummary(String runId, String sessionId, Path workspace, ScriptedRunResult result) {
        System.out.println("runId: " + runId);
        System.out.println("sessionId: " + sessionId);
        System.out.println("workspace: " + workspace.toAbsolutePath());
        System.out.println("prompt: " + prompt);
        System.out.println("planFile: " + planFile);
        System.out.println("status: " + (result.success() ? "success" : "failed"));
        System.out.println("steps:");
        for (ScriptedRunStepResult step : result.steps()) {
            System.out.println("- id: " + step.id());
            System.out.println("  tool: " + step.tool());
            System.out.println("  error: " + step.error());
            System.out.println("  output: " + step.output());
        }
    }

    private void printAgentSummary(String runId, String sessionId, Path workspace, AgentRunResult result) {
        System.out.println("runId: " + runId);
        System.out.println("mode: agent");
        System.out.println("session: " + sessionId);
        System.out.println("workspace: " + workspace.toAbsolutePath());
        System.out.println("prompt: " + prompt);
        System.out.println("status: " + (result.success() ? "success" : "failed"));
        System.out.println("turns: " + result.turnCount());
        System.out.println("final: " + result.finalMessage());
        if (result.errorReason() != null) {
            System.out.println("error: " + result.errorReason());
        }
        if (result.totalUsage() != null) {
            Usage u = result.totalUsage();
            System.out.println("usage:");
            System.out.println("  promptTokens: " + u.promptTokens());
            System.out.println("  completionTokens: " + u.completionTokens());
            System.out.println("  totalTokens: " + u.totalTokens());
            double estimatedCost = estimateCost(u);
            if (estimatedCost > 0) {
                System.out.println("  estimatedCost: " + String.format("%.6f", estimatedCost));
            }
        }
    }

    private double estimateCost(Usage usage) {
        if (modelProperties == null || modelProperties.getPricing() == null) {
            return 0.0;
        }
        TinyClawModelProperties.Pricing pricing = modelProperties.getPricing();
        return (usage.promptTokens() * pricing.getInputPricePer1M()
                + usage.completionTokens() * pricing.getOutputPricePer1M()) / 1_000_000.0;
    }
}

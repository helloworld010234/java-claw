package com.tinyclaw.adapters.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.llm.fake.FakeLlmGateway;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.AgentRunResult;
import com.tinyclaw.application.run.ScriptedRunExecutor;
import com.tinyclaw.application.run.ScriptedRunPlan;
import com.tinyclaw.application.run.ScriptedRunResult;
import com.tinyclaw.application.run.ScriptedRunStepResult;
import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.Role;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.persistence.MessageRepositoryPort;
import com.tinyclaw.ports.persistence.RunRepositoryPort;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import com.tinyclaw.ports.session.SessionService;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * CLI run 命令：执行单次 Agent 任务。
 *
 * <p>支持三种模式（按优先级）：</p>
 * <ul>
 *   <li>有 plan file：读取 JSON 计划，按顺序调用工具。</li>
 *   <li>有 --engine fake：走 AgentEngine + FakeLlmGateway 的 ReAct 循环。</li>
 *   <li>默认：校验参数、打印任务信息并退出（兼容旧行为）。</li>
 * </ul>
 */
@Component
@Scope("prototype")
@Command(
    name = "run",
    description = "Run a single agent task with the given prompt and workspace",
    mixinStandardHelpOptions = true
)
public class RunCommand implements Callable<Integer> {

    private final ScriptedRunExecutor scriptedRunExecutor;
    private final AgentEngine agentEngine;
    private final ObjectMapper objectMapper;
    private final SessionService sessionService;
    private final RunRepositoryPort runRepository;
    private final MessageRepositoryPort messageRepository;
    private final ToolExecutionRepositoryPort toolExecutionRepository;

    public RunCommand(ScriptedRunExecutor scriptedRunExecutor,
                      AgentEngine agentEngine,
                      ObjectMapper objectMapper,
                      SessionService sessionService,
                      RunRepositoryPort runRepository,
                      MessageRepositoryPort messageRepository,
                      ToolExecutionRepositoryPort toolExecutionRepository) {
        this.scriptedRunExecutor = DomainGuards.requireNonNull(scriptedRunExecutor, "scriptedRunExecutor");
        this.agentEngine = DomainGuards.requireNonNull(agentEngine, "agentEngine");
        this.objectMapper = DomainGuards.requireNonNull(objectMapper, "objectMapper");
        this.sessionService = DomainGuards.requireNonNull(sessionService, "sessionService");
        this.runRepository = runRepository;
        this.messageRepository = messageRepository;
        this.toolExecutionRepository = toolExecutionRepository;
    }

    @Option(
        names = {"--prompt"},
        required = true,
        description = "User prompt for the agent task"
    )
    private String prompt;

    @Option(
        names = {"--dir"},
        description = "Workspace directory (default: current directory)"
    )
    private String dir;

    @Option(
        names = {"--session"},
        description = "Session ID (default: auto-generated UUID)"
    )
    private String sessionId;

    @Option(
        names = {"--plan-file"},
        description = "Path to a JSON plan file describing tool steps to execute"
    )
    private String planFile;

    @Option(
        names = {"--engine"},
        description = "Execution engine: none (default), fake"
    )
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

        // Validate engine parameter before any routing
        if (engine != null && !engine.isBlank()
            && !"fake".equalsIgnoreCase(engine)
            && !"none".equalsIgnoreCase(engine)) {
            System.err.println("Invalid engine: " + engine);
            return 2;
        }

        // Priority 1: plan-file mode
        if (planFile != null && !planFile.isBlank()) {
            return runPlanFile(effectiveSessionId, workspace);
        }

        // Priority 2: agent engine (fake) mode
        if ("fake".equalsIgnoreCase(engine)) {
            return runAgentEngine(effectiveSessionId, workspace);
        }

        // Priority 3: legacy print-only mode (also handles --engine none)
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
        AgentRun run = AgentRun.start(runId, effectiveSessionId, 5, Instant.now());

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

    private Integer runAgentEngine(String effectiveSessionId, Path workspace) {
        String runId = newRunId();
        Session session = Session.create(effectiveSessionId, workspace.toAbsolutePath().toString(), Instant.now());
        AgentRun run = AgentRun.start(runId, effectiveSessionId, 5, Instant.now());

        if (runRepository != null) {
            runRepository.saveSession(session);
            runRepository.saveRunStarted(run, "agent", prompt);
        }

        // Remember how many messages exist before engine runs
        int messagesBeforeCount = sessionService.getWorkingMemory(session.id()).size();

        ToolExecutionContext context = new ToolExecutionContext(workspace).withRun(run.id(), session.id());
        FakeLlmGateway fakeLlm = FakeLlmGateway.forPrompt(prompt);
        AgentEngine fakeEngine = agentEngine.withLlmGateway(fakeLlm);
        AgentRunResult result = fakeEngine.run(run, session, prompt, context, toolExecutionRepository);

        // Persist any new messages produced by the engine
        if (messageRepository != null) {
            List<Message> messagesAfter = sessionService.getWorkingMemory(session.id());
            List<Message> newMessages = messagesAfter.stream()
                .skip(messagesBeforeCount)
                .filter(m -> m.role() != Role.SYSTEM)
                .toList();
            for (Message m : newMessages) {
                messageRepository.append(runId, session.id(), m);
            }
        }

        if (result.success()) {
            if (runRepository != null) {
                runRepository.saveRunCompleted(run.id(), result.turnCount(), Instant.now());
            }
        } else {
            if (runRepository != null) {
                runRepository.saveRunFailed(run.id(), result.turnCount(), result.errorReason(), Instant.now());
            }
        }

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
                new CommandLine(this),
                "Directory does not exist: " + dir
            );
        }
        if (!Files.isDirectory(path)) {
            throw new CommandLine.ParameterException(
                new CommandLine(this),
                "Path is not a directory: " + dir
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
    }
}

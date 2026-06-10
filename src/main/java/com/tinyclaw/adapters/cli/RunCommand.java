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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        String effectiveSessionId = sessionId != null && !sessionId.isBlank()
            ? sessionId
            : UUID.randomUUID().toString();

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

        Session session = Session.create(effectiveSessionId, workspace.toAbsolutePath().toString(), Instant.now());
        AgentRun run = AgentRun.start("run-" + effectiveSessionId, effectiveSessionId, 5, Instant.now());

        if (runRepository != null) {
            runRepository.saveSession(session);
            runRepository.saveRunStarted(run, "plan", prompt);
        }

        ToolExecutionContext context = new ToolExecutionContext(workspace);
        ScriptedRunResult result = scriptedRunExecutor.execute(plan, context, run.id(), session.id(), toolExecutionRepository);

        AgentRun finalRun;
        if (result.success()) {
            finalRun = run.complete(Instant.now());
            if (runRepository != null) {
                runRepository.saveRunCompleted(finalRun);
            }
        } else {
            String errorReason = result.steps().stream()
                .filter(ScriptedRunStepResult::error)
                .findFirst()
                .map(ScriptedRunStepResult::output)
                .orElse("Plan execution failed");
            finalRun = run.fail(errorReason, Instant.now());
            if (runRepository != null) {
                runRepository.saveRunFailed(finalRun, errorReason);
            }
        }

        printPlanSummary(effectiveSessionId, workspace, result);
        return result.success() ? 0 : 1;
    }

    private Integer runAgentEngine(String effectiveSessionId, Path workspace) {
        Session session = Session.create(effectiveSessionId, workspace.toAbsolutePath().toString(), Instant.now());
        AgentRun run = AgentRun.start("run-" + effectiveSessionId, effectiveSessionId, 5, Instant.now());

        if (runRepository != null) {
            runRepository.saveSession(session);
            runRepository.saveRunStarted(run, "agent", prompt);
        }

        // Save user prompt message
        if (messageRepository != null) {
            messageRepository.append(run.id(), session.id(), Message.user(prompt));
        }

        // Remember which messages exist before engine runs
        List<Message> messagesBefore = sessionService.getWorkingMemory(session.id());
        Set<String> messageSignaturesBefore = new HashSet<>();
        for (Message m : messagesBefore) {
            messageSignaturesBefore.add(messageSignature(m));
        }

        ToolExecutionContext context = new ToolExecutionContext(workspace);
        FakeLlmGateway fakeLlm = FakeLlmGateway.forPrompt(prompt);
        AgentEngine fakeEngine = agentEngine.withLlmGateway(fakeLlm);
        AgentRunResult result = fakeEngine.run(run, session, prompt, context);

        // Persist any new messages produced by the engine
        if (messageRepository != null) {
            List<Message> messagesAfter = sessionService.getWorkingMemory(session.id());
            for (Message m : messagesAfter) {
                if (m.role() == Role.SYSTEM) {
                    continue;
                }
                String sig = messageSignature(m);
                if (!messageSignaturesBefore.contains(sig)) {
                    messageRepository.append(run.id(), session.id(), m);
                }
            }
        }

        AgentRun finalRun;
        if (result.success()) {
            finalRun = run.complete(Instant.now());
            if (runRepository != null) {
                runRepository.saveRunCompleted(finalRun);
            }
        } else {
            finalRun = run.fail(result.errorReason(), Instant.now());
            if (runRepository != null) {
                runRepository.saveRunFailed(finalRun, result.errorReason());
            }
        }

        printAgentSummary(effectiveSessionId, workspace, result);
        return result.success() ? 0 : 1;
    }

    private String messageSignature(Message message) {
        return message.role().name() + "|" + message.content() + "|" + message.toolCallId();
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

    private void printPlanSummary(String sessionId, Path workspace, ScriptedRunResult result) {
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

    private void printAgentSummary(String sessionId, Path workspace, AgentRunResult result) {
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

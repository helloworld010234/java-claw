package com.tinyclaw.application.engine;

import com.tinyclaw.application.persistence.ToolExecutionRecord;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolDefinition;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.domain.message.Usage;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.engine.SubagentRunner;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmRequestOptions;
import com.tinyclaw.ports.llm.LlmResponse;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import com.tinyclaw.ports.reporter.Reporter;
import com.tinyclaw.ports.session.SessionService;
import com.tinyclaw.ports.tool.ToolExecutionContext;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Core ReAct agent engine.
 *
 * <p>Pure application-layer orchestrator with zero Spring dependencies.
 * The loop: build context → LLM → tool calls → tool results → next LLM turn.</p>
 */
public class AgentEngine implements SubagentRunner {

    public static final int WORKING_MEMORY_LIMIT = 50;
    private static final int MAX_SUB_TURNS = 10;
    private static final String SUBAGENT_SYSTEM_PROMPT = """
        你是一个专门负责深度探索的探路者 (Explorer Subagent)。
        你的任务是根据主架构师的指令，在当前工作区内仔细阅读代码、查阅日志，搜集足够的信息。

        【核心纪律】
        1. 你必须、且只能依靠内置工具（如 shell_command 的 find/grep，或 read_file）去寻找答案。绝对不允许凭空捏造或猜测！
        2. 如果你没有找到确切的答案，你必须继续使用工具深入搜索。
        3. 当且仅当你找到了确切的线索后，停止调用工具，直接输出一段纯文本作为你的终极汇报。主架构师会根据你的汇报来做下一步决策。
        """;

    private final LlmGateway llmGateway;
    private final ToolRegistry toolRegistry;
    private final PromptComposer promptComposer;
    private final Reporter reporter;
    private final SessionService sessionService;
    private final Clock clock;
    private final AgentContextBuilder agentContextBuilder;
    private final ToolFailureRecoveryAdvisor recoveryAdvisor;
    private final String modelName;
    private final com.tinyclaw.ports.observability.TraceReporter traceReporter;
    private final int maxToolCallsPerTurn;

    public AgentEngine(LlmGateway llmGateway,
                       ToolRegistry toolRegistry,
                       PromptComposer promptComposer,
                       Reporter reporter,
                       SessionService sessionService) {
        this(llmGateway, toolRegistry, promptComposer, reporter, sessionService, Clock.systemUTC());
    }

    public AgentEngine(LlmGateway llmGateway,
                       ToolRegistry toolRegistry,
                       PromptComposer promptComposer,
                       Reporter reporter,
                       SessionService sessionService,
                       Clock clock) {
        this(llmGateway, toolRegistry, promptComposer, reporter, sessionService, clock,
             new AgentContextBuilder(promptComposer, new WorkingMemorySelector(), new ContextCompactor()),
             new ToolFailureRecoveryAdvisor());
    }

    public AgentEngine(LlmGateway llmGateway,
                       ToolRegistry toolRegistry,
                       PromptComposer promptComposer,
                       Reporter reporter,
                       SessionService sessionService,
                       AgentContextBuilder agentContextBuilder,
                       ToolFailureRecoveryAdvisor recoveryAdvisor) {
        this(llmGateway, toolRegistry, promptComposer, reporter, sessionService, Clock.systemUTC(),
             agentContextBuilder, recoveryAdvisor);
    }

    public AgentEngine(LlmGateway llmGateway,
                       ToolRegistry toolRegistry,
                       PromptComposer promptComposer,
                       Reporter reporter,
                       SessionService sessionService,
                       Clock clock,
                       AgentContextBuilder agentContextBuilder,
                       ToolFailureRecoveryAdvisor recoveryAdvisor) {
        this(llmGateway, toolRegistry, promptComposer, reporter, sessionService, clock,
             agentContextBuilder, recoveryAdvisor, null);
    }

    public AgentEngine(LlmGateway llmGateway,
                       ToolRegistry toolRegistry,
                       PromptComposer promptComposer,
                       Reporter reporter,
                       SessionService sessionService,
                       Clock clock,
                       AgentContextBuilder agentContextBuilder,
                       ToolFailureRecoveryAdvisor recoveryAdvisor,
                       String modelName) {
        this(llmGateway, toolRegistry, promptComposer, reporter, sessionService, clock,
             agentContextBuilder, recoveryAdvisor, modelName,
             new com.tinyclaw.application.observability.NoOpTraceReporter(), 8);
    }

    public AgentEngine(LlmGateway llmGateway,
                       ToolRegistry toolRegistry,
                       PromptComposer promptComposer,
                       Reporter reporter,
                       SessionService sessionService,
                       Clock clock,
                       AgentContextBuilder agentContextBuilder,
                       ToolFailureRecoveryAdvisor recoveryAdvisor,
                       String modelName,
                       com.tinyclaw.ports.observability.TraceReporter traceReporter) {
        this(llmGateway, toolRegistry, promptComposer, reporter, sessionService, clock,
             agentContextBuilder, recoveryAdvisor, modelName, traceReporter, 8);
    }

    public AgentEngine(LlmGateway llmGateway,
                       ToolRegistry toolRegistry,
                       PromptComposer promptComposer,
                       Reporter reporter,
                       SessionService sessionService,
                       Clock clock,
                       AgentContextBuilder agentContextBuilder,
                       ToolFailureRecoveryAdvisor recoveryAdvisor,
                       String modelName,
                       com.tinyclaw.ports.observability.TraceReporter traceReporter,
                       int maxToolCallsPerTurn) {
        this.llmGateway = DomainGuards.requireNonNull(llmGateway, "llmGateway");
        this.toolRegistry = DomainGuards.requireNonNull(toolRegistry, "toolRegistry");
        this.promptComposer = DomainGuards.requireNonNull(promptComposer, "promptComposer");
        this.reporter = DomainGuards.requireNonNull(reporter, "reporter");
        this.sessionService = DomainGuards.requireNonNull(sessionService, "sessionService");
        this.clock = DomainGuards.requireNonNull(clock, "clock");
        this.agentContextBuilder = DomainGuards.requireNonNull(agentContextBuilder, "agentContextBuilder");
        this.recoveryAdvisor = DomainGuards.requireNonNull(recoveryAdvisor, "recoveryAdvisor");
        this.modelName = modelName != null && !modelName.isBlank() ? modelName : "";
        this.traceReporter = traceReporter != null ? traceReporter : new com.tinyclaw.application.observability.NoOpTraceReporter();
        this.maxToolCallsPerTurn = maxToolCallsPerTurn > 0 ? maxToolCallsPerTurn : 8;
    }

    /**
     * Returns a new AgentEngine instance with the given LLM gateway,
     * reusing all other dependencies.
     */
    public AgentEngine withLlmGateway(LlmGateway llmGateway) {
        return new AgentEngine(llmGateway, toolRegistry, promptComposer, reporter, sessionService, clock,
            agentContextBuilder, recoveryAdvisor, modelName, traceReporter, maxToolCallsPerTurn);
    }

    /**
     * Returns a new AgentEngine instance with the given model name,
     * reusing all other dependencies.
     */
    public AgentEngine withModelName(String modelName) {
        return new AgentEngine(llmGateway, toolRegistry, promptComposer, reporter, sessionService, clock,
            agentContextBuilder, recoveryAdvisor, modelName, traceReporter, maxToolCallsPerTurn);
    }

    /**
     * Execute a ReAct agent run.
     *
     * @param run         the run state machine (must be in RUNNING status)
     * @param session     the session for message persistence
     * @param userPrompt  the initial user prompt
     * @param toolContext shared tool execution context
     * @return the run result
     */
    public AgentRunResult run(AgentRun run, Session session, String userPrompt, ToolExecutionContext toolContext) {
        return run(run, session, userPrompt, toolContext, null);
    }

    /**
     * Execute a ReAct agent run with optional tool execution audit.
     *
     * @param run                      the run state machine
     * @param session                  the session for message persistence
     * @param userPrompt               the initial user prompt
     * @param toolContext              shared tool execution context
     * @param toolExecutionRepository  optional repository for tool execution audit
     * @return the run result
     */
    public AgentRunResult run(AgentRun run, Session session, String userPrompt,
                              ToolExecutionContext toolContext,
                              ToolExecutionRepositoryPort toolExecutionRepository) {
        DomainGuards.requireNonNull(run, "run");
        DomainGuards.requireNonNull(session, "session");
        DomainGuards.requireNonNull(userPrompt, "userPrompt");
        DomainGuards.requireNonNull(toolContext, "toolContext");

        var span = traceReporter.startSpan("AgentEngine.run", Map.of(
            "run.id", run.id(),
            "session.id", session.id()
        ));

        try {
            sessionService.appendMessage(session.id(), Message.user(userPrompt));
            reporter.onThinkingStarted(run.id());

            AgentRun currentRun = run;
            String lastAssistantContent = "";
            boolean anyToolFailed = false;
            String toolFailureReason = null;
            ToolFailureReminder failureReminder = new ToolFailureReminder();
            Usage totalUsage = null;

            while (currentRun.currentTurn() < currentRun.maxTurns()) {
                currentRun = currentRun.nextTurn();
                traceReporter.addAttribute(span, "turn", String.valueOf(currentRun.currentTurn()));

                List<Message> messages = agentContextBuilder.build(
                    session.workDir(),
                    sessionService.getWorkingMemory(session.id()),
                    WORKING_MEMORY_LIMIT
                );

                List<ToolDefinition> availableTools = toolRegistry.availableTools();
                LlmRequest request = new LlmRequest(
                    modelName,
                    messages,
                    availableTools,
                    LlmRequestOptions.defaults()
                );

                LlmResponse response;
                try {
                    response = llmGateway.generate(request);
                    if (response.usage() != null) {
                        reporter.onUsage(run.id(), session.id(), response.usage(), modelName);
                        if (totalUsage == null) {
                            totalUsage = response.usage();
                        } else {
                            totalUsage = new Usage(
                                totalUsage.promptTokens() + response.usage().promptTokens(),
                                totalUsage.completionTokens() + response.usage().completionTokens()
                            );
                        }
                    }
                } catch (Exception e) {
                    String reason = "LLM generation failed: " + e.getMessage();
                    currentRun = currentRun.fail(reason, clock.instant());
                    reporter.onRunFailed(currentRun.id(), reason);
                    traceReporter.addAttribute(span, "error", reason);
                    traceReporter.endSpan(span);
                    return new AgentRunResult(false, lastAssistantContent, currentRun.currentTurn(), reason, totalUsage);
                }

                lastAssistantContent = response.content();

                Message assistantMsg;
                if (response.hasToolCalls()) {
                    assistantMsg = Message.assistantWithToolCalls(response.content(), response.toolCalls());
                } else {
                    assistantMsg = Message.assistant(response.content());
                }
                sessionService.appendMessage(session.id(), assistantMsg);
                reporter.onAssistantMessage(currentRun.id(), response.content());

                if (!response.hasToolCalls()) {
                    if (anyToolFailed) {
                        currentRun = currentRun.fail(toolFailureReason, clock.instant());
                        AgentRunResult result = new AgentRunResult(false, response.content(), currentRun.currentTurn(), toolFailureReason, totalUsage);
                        reporter.onRunFailed(currentRun.id(), toolFailureReason);
                        traceReporter.addAttribute(span, "error", toolFailureReason);
                        traceReporter.endSpan(span);
                        return result;
                    }
                    currentRun = currentRun.complete(clock.instant());
                    AgentRunResult result = new AgentRunResult(true, response.content(), currentRun.currentTurn(), null, totalUsage);
                    reporter.onRunCompleted(currentRun.id(), result);
                    traceReporter.endSpan(span);
                    return result;
                }

                if (response.toolCalls().size() > maxToolCallsPerTurn) {
                    String reason = "Tool calls per turn exceeded limit: " + response.toolCalls().size() + " > " + maxToolCallsPerTurn;
                    currentRun = currentRun.fail(reason, clock.instant());
                    reporter.onRunFailed(currentRun.id(), reason);
                    traceReporter.addAttribute(span, "error", reason);
                    traceReporter.endSpan(span);
                    return new AgentRunResult(false, lastAssistantContent, currentRun.currentTurn(), reason, totalUsage);
                }

                for (ToolCall toolCall : response.toolCalls()) {
                    reporter.onToolCall(currentRun.id(), toolCall);
                    Instant startedAt = clock.instant();
                    ToolResult toolResult = toolRegistry.execute(toolCall, toolContext);
                    Instant completedAt = clock.instant();
                    reporter.onToolResult(currentRun.id(), toolResult);

                    traceReporter.recordEvent(span, "tool.execute", Map.of(
                        "tool.name", toolCall.name(),
                        "tool.error", String.valueOf(toolResult.error())
                    ));

                    if (toolExecutionRepository != null) {
                        ToolExecutionRecord record = new ToolExecutionRecord(
                            UUID.randomUUID().toString(),
                            currentRun.id(),
                            session.id(),
                            toolCall.id(),
                            toolCall.name(),
                            toolCall.argumentsJson(),
                            toolResult.output(),
                            toolResult.error(),
                            startedAt,
                            completedAt
                        );
                        toolExecutionRepository.append(currentRun.id(), record);
                    }

                    String observationOutput = toolResult.output();
                    if (toolResult.error()) {
                        anyToolFailed = true;
                        toolFailureReason = "Tool '" + toolCall.name() + "' failed: " + toolResult.output();
                        observationOutput = recoveryAdvisor.advise(toolCall.name(), observationOutput);
                    }

                    Message observation = Message.toolObservation(toolCall.id(), observationOutput);
                    sessionService.appendMessage(session.id(), observation);

                    Optional<Message> reminder = failureReminder.onToolResult(toolCall, toolResult);
                    reminder.ifPresent(r -> sessionService.appendMessage(session.id(), r));
                }
            }

            String reason = "Max turns (" + run.maxTurns() + ") exceeded without completion";
            currentRun = currentRun.fail(reason, clock.instant());
            AgentRunResult result = new AgentRunResult(false, lastAssistantContent, currentRun.currentTurn(), reason, totalUsage);
            reporter.onRunFailed(currentRun.id(), reason);
            traceReporter.addAttribute(span, "error", reason);
            traceReporter.endSpan(span);
            return result;
        } catch (Exception e) {
            traceReporter.addAttribute(span, "error", "Unexpected: " + e.getMessage());
            traceReporter.endSpan(span);
            throw e;
        }
    }

    /**
     * 运行子 Agent 执行探索任务。
     *
     * <p>子 Agent 使用独立的临时 Session，只读工具集，最多运行 {@value #MAX_SUB_TURNS} 轮。</p>
     */
    @Override
    public String runSub(String taskPrompt, ToolRegistry readOnlyRegistry, Reporter reporter, String workDir) {
        DomainGuards.requireNonBlank(taskPrompt, "taskPrompt");
        DomainGuards.requireNonNull(readOnlyRegistry, "readOnlyRegistry");
        DomainGuards.requireNonBlank(workDir, "workDir");

        var span = traceReporter.startSpan("AgentEngine.runSub", Map.of(
            "task", taskPrompt.substring(0, Math.min(taskPrompt.length(), 100))
        ));

        List<Message> contextHistory = new ArrayList<>();
        contextHistory.add(Message.system(SUBAGENT_SYSTEM_PROMPT));
        contextHistory.add(Message.user(taskPrompt));

        int turnCount = 0;
        while (turnCount < MAX_SUB_TURNS) {
            turnCount++;
            traceReporter.addAttribute(span, "sub.turn", String.valueOf(turnCount));

            List<ToolDefinition> availableTools = readOnlyRegistry.availableTools();
            LlmRequest request = new LlmRequest(
                modelName,
                contextHistory,
                availableTools,
                LlmRequestOptions.defaults()
            );

            LlmResponse response;
            try {
                response = llmGateway.generate(request);
            } catch (Exception e) {
                traceReporter.addAttribute(span, "error", "LLM failure: " + e.getMessage());
                traceReporter.endSpan(span);
                return "子智能体推理失败: " + e.getMessage();
            }

            Message assistantMsg;
            if (response.hasToolCalls()) {
                assistantMsg = Message.assistantWithToolCalls(response.content(), response.toolCalls());
            } else {
                assistantMsg = Message.assistant(response.content());
            }
            contextHistory.add(assistantMsg);

            if (!response.hasToolCalls()) {
                traceReporter.endSpan(span);
                return response.content();
            }

            for (ToolCall toolCall : response.toolCalls()) {
                if (reporter != null) {
                    reporter.onToolCall("subagent", ToolCall.of(toolCall.id(), "[Subagent] " + toolCall.name(), toolCall.argumentsJson()));
                }

                ToolResult result = readOnlyRegistry.execute(toolCall, new ToolExecutionContext(Path.of(workDir)));

                traceReporter.recordEvent(span, "subagent.tool.execute", Map.of(
                    "tool.name", toolCall.name(),
                    "tool.error", String.valueOf(result.error())
                ));

                if (reporter != null) {
                    String displayOutput = result.output();
                    if (displayOutput.length() > 200) {
                        displayOutput = displayOutput.substring(0, 200) + "... (已截断)";
                    }
                    reporter.onToolResult("subagent", ToolResult.success(toolCall.id(), displayOutput));
                }

                String finalOutput = result.output();
                if (result.error()) {
                    finalOutput = recoveryAdvisor.advise(toolCall.name(), finalOutput);
                }

                contextHistory.add(Message.toolObservation(toolCall.id(), finalOutput));
            }
        }

        String recallMsg = "子智能体探索过于深入，超过 " + MAX_SUB_TURNS + " 轮被强制召回，请主 Agent 给它更明确的指令";
        traceReporter.addAttribute(span, "error", "max_turns_exceeded");
        traceReporter.endSpan(span);
        return recallMsg;
    }
}

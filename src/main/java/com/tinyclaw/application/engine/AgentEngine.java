package com.tinyclaw.application.engine;

import com.tinyclaw.ports.persistence.ToolExecutionRecord;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolDefinition;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.domain.message.Usage;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.tool.ToolApprovalRequiredException;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.engine.SubagentRunner;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmRequestOptions;
import com.tinyclaw.ports.llm.LlmResponse;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import com.tinyclaw.ports.reporter.Reporter;
import com.tinyclaw.ports.session.SessionService;
import com.tinyclaw.ports.tool.ToolCatalog;
import com.tinyclaw.ports.tool.ToolExecutionContext;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Core ReAct agent engine.
 *
 * <p>Pure application-layer orchestrator with zero Spring dependencies.
 * The loop: build context → LLM → tool calls → tool results → next LLM turn.</p>
 *
 * <p>Supports optional Thinking/Action two-phase execution (Go baseline alignment):
 * <ul>
 *   <li>Thinking phase: no tools exposed, produces intermediate reasoning</li>
 *   <li>Action phase: tools exposed, may return tool calls</li>
 *   <li>Final assistant message merges both phases' content</li>
 * </ul></p>
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
    private final boolean enableThinking;
    private final Executor toolExecutor;

    private static final AtomicInteger DEFAULT_THREAD_COUNTER = new AtomicInteger(0);

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
             new com.tinyclaw.ports.observability.NoOpTraceReporter(), 8, false);
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
             agentContextBuilder, recoveryAdvisor, modelName, traceReporter, 8, false);
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
        this(llmGateway, toolRegistry, promptComposer, reporter, sessionService, clock,
             agentContextBuilder, recoveryAdvisor, modelName, traceReporter, maxToolCallsPerTurn, false);
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
                       int maxToolCallsPerTurn,
                       boolean enableThinking) {
        this(llmGateway, toolRegistry, promptComposer, reporter, sessionService, clock,
             agentContextBuilder, recoveryAdvisor, modelName, traceReporter, maxToolCallsPerTurn,
             enableThinking, null);
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
                       int maxToolCallsPerTurn,
                       boolean enableThinking,
                       Executor toolExecutor) {
        this.llmGateway = DomainGuards.requireNonNull(llmGateway, "llmGateway");
        this.toolRegistry = DomainGuards.requireNonNull(toolRegistry, "toolRegistry");
        this.promptComposer = DomainGuards.requireNonNull(promptComposer, "promptComposer");
        this.reporter = DomainGuards.requireNonNull(reporter, "reporter");
        this.sessionService = DomainGuards.requireNonNull(sessionService, "sessionService");
        this.clock = DomainGuards.requireNonNull(clock, "clock");
        this.agentContextBuilder = DomainGuards.requireNonNull(agentContextBuilder, "agentContextBuilder");
        this.recoveryAdvisor = DomainGuards.requireNonNull(recoveryAdvisor, "recoveryAdvisor");
        this.modelName = modelName != null && !modelName.isBlank() ? modelName : "";
        this.traceReporter = traceReporter != null ? traceReporter : new com.tinyclaw.ports.observability.NoOpTraceReporter();
        this.maxToolCallsPerTurn = maxToolCallsPerTurn > 0 ? maxToolCallsPerTurn : 8;
        this.enableThinking = enableThinking;
        this.toolExecutor = toolExecutor != null ? toolExecutor : createDefaultToolExecutor();
    }

    private static Executor createDefaultToolExecutor() {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        return Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "tinyclaw-tool-default-" + DEFAULT_THREAD_COUNTER.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Returns a new AgentEngine instance with the given LLM gateway,
     * reusing all other dependencies.
     */
    public AgentEngine withLlmGateway(LlmGateway llmGateway) {
        return new AgentEngine(llmGateway, toolRegistry, promptComposer, reporter, sessionService, clock,
            agentContextBuilder, recoveryAdvisor, modelName, traceReporter, maxToolCallsPerTurn, enableThinking, toolExecutor);
    }

    /**
     * Returns a new AgentEngine instance with the given model name,
     * reusing all other dependencies.
     */
    public AgentEngine withModelName(String modelName) {
        return new AgentEngine(llmGateway, toolRegistry, promptComposer, reporter, sessionService, clock,
            agentContextBuilder, recoveryAdvisor, modelName, traceReporter, maxToolCallsPerTurn, enableThinking, toolExecutor);
    }

    /**
     * Returns a new AgentEngine instance with Thinking enabled,
     * reusing all other dependencies.
     */
    public AgentEngine withEnableThinking(boolean enableThinking) {
        return new AgentEngine(llmGateway, toolRegistry, promptComposer, reporter, sessionService, clock,
            agentContextBuilder, recoveryAdvisor, modelName, traceReporter, maxToolCallsPerTurn, enableThinking, toolExecutor);
    }

    /**
     * Returns a new AgentEngine instance with the given tool registry,
     * reusing all other dependencies.
     */
    public AgentEngine withToolRegistry(ToolRegistry toolRegistry) {
        return new AgentEngine(llmGateway, toolRegistry, promptComposer, reporter, sessionService, clock,
            agentContextBuilder, recoveryAdvisor, modelName, traceReporter, maxToolCallsPerTurn, enableThinking, toolExecutor);
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
            return executeLoop(run, session, toolContext, toolExecutionRepository, span);
        } catch (Exception e) {
            traceReporter.addAttribute(span, "error", "Unexpected: " + e.getMessage());
            traceReporter.endSpan(span);
            throw e;
        }
    }

    /**
     * Resume a run that is paused waiting for approval.
     *
     * <p>Assumes the approved tool has already been executed and its observation
     * appended to the session. This method continues the ReAct loop from the
     * current turn, calling the LLM for the next action.</p>
     *
     * @param run                      the run state machine (must be in WAITING_APPROVAL status)
     * @param session                  the session for message persistence
     * @param toolContext              shared tool execution context
     * @param toolExecutionRepository  optional repository for tool execution audit
     * @return the run result
     */
    public AgentRunResult resume(AgentRun run, Session session, ToolExecutionContext toolContext,
                                 ToolExecutionRepositoryPort toolExecutionRepository) {
        DomainGuards.requireNonNull(run, "run");
        DomainGuards.requireNonNull(session, "session");
        DomainGuards.requireNonNull(toolContext, "toolContext");

        var span = traceReporter.startSpan("AgentEngine.resume", Map.of(
            "run.id", run.id(),
            "session.id", session.id()
        ));

        try {
            return executeLoop(run, session, toolContext, toolExecutionRepository, span);
        } catch (Exception e) {
            traceReporter.addAttribute(span, "error", "Unexpected: " + e.getMessage());
            traceReporter.endSpan(span);
            throw e;
        }
    }

    private AgentRunResult executeLoop(AgentRun run,
                                       Session session,
                                       ToolExecutionContext toolContext,
                                       ToolExecutionRepositoryPort toolExecutionRepository,
                                       com.tinyclaw.ports.observability.TraceReporter.SpanHandle span) {
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

            // Phase 1: Thinking (optional)
            String thinkingContent = "";
            if (enableThinking) {
                LlmRequest thinkRequest = new LlmRequest(
                    modelName,
                    messages,
                    List.of(), // no tools during thinking
                    LlmRequestOptions.defaults()
                );
                LlmResponse thinkResponse;
                try {
                    thinkResponse = llmGateway.generate(thinkRequest);
                    if (thinkResponse.usage() != null) {
                        reporter.onUsage(run.id(), session.id(), thinkResponse.usage(), modelName);
                        totalUsage = accumulateUsage(totalUsage, thinkResponse.usage());
                    }
                } catch (Exception e) {
                    String reason = "Thinking phase failed: " + e.getMessage();
                    currentRun = currentRun.fail(reason, clock.instant());
                    reporter.onRunFailed(currentRun.id(), reason);
                    traceReporter.addAttribute(span, "error", reason);
                    traceReporter.endSpan(span);
                    return new AgentRunResult(false, lastAssistantContent, currentRun.currentTurn(), reason, totalUsage);
                }
                thinkingContent = thinkResponse.content();
                if (!thinkingContent.isBlank()) {
                    messages = new ArrayList<>(messages);
                    messages.add(Message.assistant(thinkingContent));
                }
            }

            // Phase 2: Action
            List<ToolDefinition> availableTools = toolRegistry.availableTools();
            LlmRequest actionRequest = new LlmRequest(
                modelName,
                messages,
                availableTools,
                LlmRequestOptions.defaults()
            );

            LlmResponse response;
            try {
                response = llmGateway.generate(actionRequest);
                if (response.usage() != null) {
                    reporter.onUsage(run.id(), session.id(), response.usage(), modelName);
                    totalUsage = accumulateUsage(totalUsage, response.usage());
                }
            } catch (Exception e) {
                String phaseLabel = enableThinking ? "Action phase" : "LLM generation";
                String reason = phaseLabel + " failed: " + e.getMessage();
                currentRun = currentRun.fail(reason, clock.instant());
                reporter.onRunFailed(currentRun.id(), reason);
                traceReporter.addAttribute(span, "error", reason);
                traceReporter.endSpan(span);
                return new AgentRunResult(false, lastAssistantContent, currentRun.currentTurn(), reason, totalUsage);
            }

            // Merge thinking + action content for the final assistant message
            String mergedContent;
            if (thinkingContent.isBlank()) {
                mergedContent = response.content();
            } else {
                mergedContent = thinkingContent + "\n" + response.content();
            }
            mergedContent = mergedContent.trim();
            lastAssistantContent = mergedContent;

            Message assistantMsg;
            if (response.hasToolCalls()) {
                assistantMsg = Message.assistantWithToolCalls(mergedContent, response.toolCalls());
            } else {
                assistantMsg = Message.assistant(mergedContent);
            }
            sessionService.appendMessage(session.id(), assistantMsg);
            reporter.onAssistantMessage(currentRun.id(), mergedContent);

            if (!response.hasToolCalls()) {
                if (anyToolFailed) {
                    currentRun = currentRun.fail(toolFailureReason, clock.instant());
                    AgentRunResult result = new AgentRunResult(false, mergedContent, currentRun.currentTurn(), toolFailureReason, totalUsage);
                    reporter.onRunFailed(currentRun.id(), toolFailureReason);
                    traceReporter.addAttribute(span, "error", toolFailureReason);
                    traceReporter.endSpan(span);
                    return result;
                }
                currentRun = currentRun.complete(clock.instant());
                AgentRunResult result = new AgentRunResult(true, mergedContent, currentRun.currentTurn(), null, totalUsage);
                reporter.onRunCompleted(currentRun.id(), new com.tinyclaw.ports.reporter.RunReportResult(result.success(), result.finalMessage(), result.turnCount(), result.errorReason(), result.totalUsage()));
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

            // Execute tool calls concurrently, preserving order for observations
            List<ToolCall> toolCalls = response.toolCalls();
            List<CompletableFuture<ToolExecutionOutcome>> futures = new ArrayList<>(toolCalls.size());
            for (ToolCall toolCall : toolCalls) {
                reporter.onToolCall(currentRun.id(), toolCall);
                CompletableFuture<ToolExecutionOutcome> future = CompletableFuture.supplyAsync(() -> {
                    Instant startedAt = clock.instant();
                    ToolResult result;
                    try {
                        result = toolRegistry.execute(toolCall, toolContext);
                    } catch (ToolApprovalRequiredException e) {
                        throw e;
                    } catch (Exception e) {
                        result = ToolResult.failure(toolCall.id(), "Tool execution failed: " + e.getMessage());
                    }
                    Instant completedAt = clock.instant();
                    return new ToolExecutionOutcome(toolCall, result, startedAt, completedAt);
                }, toolExecutor);
                futures.add(future);
            }

            boolean interrupted = false;
            for (int i = 0; i < toolCalls.size(); i++) {
                ToolCall toolCall = toolCalls.get(i);
                ToolExecutionOutcome outcome;
                try {
                    outcome = futures.get(i).get();
                } catch (InterruptedException e) {
                    interrupted = true;
                    // Cancel current and all remaining futures
                    for (int j = i; j < futures.size(); j++) {
                        futures.get(j).cancel(true);
                    }
                    break;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof ToolApprovalRequiredException approvalEx) {
                        // Cancel remaining futures; pause the run for approval
                        for (int j = i; j < futures.size(); j++) {
                            futures.get(j).cancel(true);
                        }
                        currentRun = currentRun.waitForApproval();
                        appendApprovalAuditRecord(
                            currentRun,
                            session,
                            approvalEx,
                            toolExecutionRepository
                        );
                        reporter.onRunWaitingForApproval(
                            currentRun.id(),
                            approvalEx.approvalId(),
                            approvalEx.toolCall().name(),
                            approvalEx.argumentsPreview()
                        );
                        traceReporter.addAttribute(span, "approval.id", approvalEx.approvalId());
                        traceReporter.addAttribute(span, "approval.tool", approvalEx.toolCall().name());
                        traceReporter.endSpan(span);
                        return AgentRunResult.waitingForApproval(
                            approvalEx.approvalId(),
                            approvalEx.toolCall().name(),
                            currentRun.currentTurn()
                        );
                    }
                    // Cancel remaining futures; convert this tool's exception to failure
                    for (int j = i + 1; j < futures.size(); j++) {
                        futures.get(j).cancel(true);
                    }
                    outcome = new ToolExecutionOutcome(
                        toolCall,
                        ToolResult.failure(toolCall.id(), "Tool execution failed: " + cause.getMessage()),
                        clock.instant(),
                        clock.instant()
                    );
                }

                ToolResult toolResult = outcome.toolResult();
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
                        outcome.startedAt(),
                        outcome.completedAt()
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

            if (interrupted) {
                Thread.currentThread().interrupt();
                String reason = "Tool execution interrupted";
                currentRun = currentRun.fail(reason, clock.instant());
                reporter.onRunFailed(currentRun.id(), reason);
                traceReporter.addAttribute(span, "error", reason);
                traceReporter.endSpan(span);
                return new AgentRunResult(false, lastAssistantContent, currentRun.currentTurn(), reason, totalUsage);
            }
        }

        String reason = "Max turns (" + run.maxTurns() + ") exceeded without completion";
        currentRun = currentRun.fail(reason, clock.instant());
        AgentRunResult result = new AgentRunResult(false, lastAssistantContent, currentRun.currentTurn(), reason, totalUsage);
        reporter.onRunFailed(currentRun.id(), reason);
        traceReporter.addAttribute(span, "error", reason);
        traceReporter.endSpan(span);
        return result;
    }

    private void appendApprovalAuditRecord(AgentRun currentRun,
                                           Session session,
                                           ToolApprovalRequiredException approvalEx,
                                           ToolExecutionRepositoryPort toolExecutionRepository) {
        if (toolExecutionRepository == null) {
            return;
        }
        Instant now = clock.instant();
        ToolCall toolCall = approvalEx.toolCall();
        ToolExecutionRecord record = new ToolExecutionRecord(
            UUID.randomUUID().toString(),
            currentRun.id(),
            session.id(),
            toolCall.id(),
            toolCall.name(),
            toolCall.argumentsJson(),
            approvalEx.getMessage(),
            true,
            now,
            now
        );
        toolExecutionRepository.append(currentRun.id(), record);
    }

    private Usage accumulateUsage(Usage total, Usage delta) {
        if (total == null) {
            return delta;
        }
        return new Usage(
            total.promptTokens() + delta.promptTokens(),
            total.completionTokens() + delta.completionTokens()
        );
    }

    /**
     * 运行子 Agent 执行探索任务。
     *
     * <p>子 Agent 使用独立的临时 Session，只读工具集，最多运行 {@value #MAX_SUB_TURNS} 轮。</p>
     */
    @Override
    public String runSub(String taskPrompt, ToolCatalog readOnlyCatalog, Reporter reporter, String workDir) {
        DomainGuards.requireNonBlank(taskPrompt, "taskPrompt");
        DomainGuards.requireNonNull(readOnlyCatalog, "readOnlyCatalog");
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

            List<ToolDefinition> availableTools = readOnlyCatalog.availableTools();
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

                ToolResult result = readOnlyCatalog.execute(toolCall, new ToolExecutionContext(Path.of(workDir)));

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

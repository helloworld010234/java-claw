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
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmRequestOptions;
import com.tinyclaw.ports.llm.LlmResponse;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import com.tinyclaw.ports.reporter.Reporter;
import com.tinyclaw.ports.session.SessionService;
import com.tinyclaw.ports.tool.ToolExecutionContext;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Core ReAct agent engine.
 *
 * <p>Pure application-layer orchestrator with zero Spring dependencies.
 * The loop: build context → LLM → tool calls → tool results → next LLM turn.</p>
 */
public class AgentEngine {

    public static final int WORKING_MEMORY_LIMIT = 50;

    private final LlmGateway llmGateway;
    private final ToolRegistry toolRegistry;
    private final PromptComposer promptComposer;
    private final Reporter reporter;
    private final SessionService sessionService;
    private final Clock clock;
    private final AgentContextBuilder agentContextBuilder;
    private final ToolFailureRecoveryAdvisor recoveryAdvisor;
    private final String modelName;

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
        this.llmGateway = DomainGuards.requireNonNull(llmGateway, "llmGateway");
        this.toolRegistry = DomainGuards.requireNonNull(toolRegistry, "toolRegistry");
        this.promptComposer = DomainGuards.requireNonNull(promptComposer, "promptComposer");
        this.reporter = DomainGuards.requireNonNull(reporter, "reporter");
        this.sessionService = DomainGuards.requireNonNull(sessionService, "sessionService");
        this.clock = DomainGuards.requireNonNull(clock, "clock");
        this.agentContextBuilder = DomainGuards.requireNonNull(agentContextBuilder, "agentContextBuilder");
        this.recoveryAdvisor = DomainGuards.requireNonNull(recoveryAdvisor, "recoveryAdvisor");
        this.modelName = modelName != null && !modelName.isBlank() ? modelName : "";
    }

    /**
     * Returns a new AgentEngine instance with the given LLM gateway,
     * reusing all other dependencies.
     */
    public AgentEngine withLlmGateway(LlmGateway llmGateway) {
        return new AgentEngine(llmGateway, toolRegistry, promptComposer, reporter, sessionService, clock,
            agentContextBuilder, recoveryAdvisor, modelName);
    }

    /**
     * Returns a new AgentEngine instance with the given model name,
     * reusing all other dependencies.
     */
    public AgentEngine withModelName(String modelName) {
        return new AgentEngine(llmGateway, toolRegistry, promptComposer, reporter, sessionService, clock,
            agentContextBuilder, recoveryAdvisor, modelName);
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
                    return result;
                }
                currentRun = currentRun.complete(clock.instant());
                AgentRunResult result = new AgentRunResult(true, response.content(), currentRun.currentTurn(), null, totalUsage);
                reporter.onRunCompleted(currentRun.id(), result);
                return result;
            }

            for (ToolCall toolCall : response.toolCalls()) {
                reporter.onToolCall(currentRun.id(), toolCall);
                Instant startedAt = clock.instant();
                ToolResult toolResult = toolRegistry.execute(toolCall, toolContext);
                Instant completedAt = clock.instant();
                reporter.onToolResult(currentRun.id(), toolResult);

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
        return result;
    }
}

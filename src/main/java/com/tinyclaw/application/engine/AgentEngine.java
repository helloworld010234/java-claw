package com.tinyclaw.application.engine;

import com.tinyclaw.application.persistence.ToolExecutionRecord;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolDefinition;
import com.tinyclaw.domain.message.ToolResult;
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
import java.util.UUID;

/**
 * Core ReAct agent engine.
 *
 * <p>Pure application-layer orchestrator with zero Spring dependencies.
 * The loop: LLM → tool calls → tool results → next LLM turn.</p>
 */
public class AgentEngine {

    private final LlmGateway llmGateway;
    private final ToolRegistry toolRegistry;
    private final PromptComposer promptComposer;
    private final Reporter reporter;
    private final SessionService sessionService;
    private final Clock clock;

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
        this.llmGateway = DomainGuards.requireNonNull(llmGateway, "llmGateway");
        this.toolRegistry = DomainGuards.requireNonNull(toolRegistry, "toolRegistry");
        this.promptComposer = DomainGuards.requireNonNull(promptComposer, "promptComposer");
        this.reporter = DomainGuards.requireNonNull(reporter, "reporter");
        this.sessionService = DomainGuards.requireNonNull(sessionService, "sessionService");
        this.clock = DomainGuards.requireNonNull(clock, "clock");
    }

    /**
     * Returns a new AgentEngine instance with the given LLM gateway,
     * reusing all other dependencies.
     */
    public AgentEngine withLlmGateway(LlmGateway llmGateway) {
        return new AgentEngine(llmGateway, toolRegistry, promptComposer, reporter, sessionService, clock);
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

        while (currentRun.currentTurn() < currentRun.maxTurns()) {
            currentRun = currentRun.nextTurn();

            Message systemMsg = promptComposer.compose(session.workDir());
            List<Message> workingMemory = sessionService.getWorkingMemory(session.id());

            List<Message> messages = new ArrayList<>(workingMemory.size() + 1);
            messages.add(systemMsg);
            messages.addAll(workingMemory);

            List<ToolDefinition> availableTools = toolRegistry.availableTools();
            LlmRequest request = new LlmRequest(
                "default-model",
                messages,
                availableTools,
                LlmRequestOptions.defaults()
            );

            LlmResponse response;
            try {
                response = llmGateway.generate(request);
            } catch (Exception e) {
                String reason = "LLM generation failed: " + e.getMessage();
                currentRun = currentRun.fail(reason, clock.instant());
                reporter.onRunFailed(currentRun.id(), reason);
                return new AgentRunResult(false, lastAssistantContent, currentRun.currentTurn(), reason);
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
                    AgentRunResult result = new AgentRunResult(false, response.content(), currentRun.currentTurn(), toolFailureReason);
                    reporter.onRunFailed(currentRun.id(), toolFailureReason);
                    return result;
                }
                currentRun = currentRun.complete(clock.instant());
                AgentRunResult result = new AgentRunResult(true, response.content(), currentRun.currentTurn(), null);
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

                if (toolResult.error()) {
                    anyToolFailed = true;
                    toolFailureReason = "Tool '" + toolCall.name() + "' failed: " + toolResult.output();
                }
                Message observation = Message.toolObservation(toolCall.id(), toolResult.output());
                sessionService.appendMessage(session.id(), observation);
            }
        }

        String reason = "Max turns (" + run.maxTurns() + ") exceeded without completion";
        currentRun = currentRun.fail(reason, clock.instant());
        AgentRunResult result = new AgentRunResult(false, lastAssistantContent, currentRun.currentTurn(), reason);
        reporter.onRunFailed(currentRun.id(), reason);
        return result;
    }
}

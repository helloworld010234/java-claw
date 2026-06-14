package com.tinyclaw.application.approval;

import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.AgentRunResult;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.run.AgentRunStatus;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.persistence.AgentRunSummary;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
import com.tinyclaw.ports.persistence.MessageRepositoryPort;
import com.tinyclaw.ports.persistence.RunRepositoryPort;
import com.tinyclaw.ports.persistence.ToolExecutionRecord;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import com.tinyclaw.ports.session.SessionService;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service that resumes a previously approved tool call and continues
 * the ReAct loop.
 *
 * <p>Looks up the approval, the original run, and the original tool execution
 * audit record, then re-executes the same tool call. The new execution result
 * is appended as a fresh audit record and the run status is updated.</p>
 *
 * <p>After the approved tool succeeds, the service delegates back to
 * {@link AgentEngine#resume} to continue subsequent LLM turns. If the tool
 * fails, the run is marked failed with an auditable reason.</p>
 *
 * <p><strong>Single-use semantics:</strong> once a tool call has been executed
 * (whether it succeeds or fails), the approval is marked {@code RESUMED}
 * and can never be resumed again. This prevents replay attacks on side-effect
 * tools such as {@code shell_command} or {@code write_file}.</p>
 */
public class ApprovalResumeService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalResumeService.class);

    private final ApprovalRepositoryPort approvalRepository;
    private final RunRepositoryPort runRepository;
    private final ToolExecutionRepositoryPort toolExecutionRepository;
    private final ToolRegistry toolRegistry;
    private final ApprovalResumeLockRegistry lockRegistry;
    private final Clock clock;
    private final SessionService sessionService;
    private final MessageRepositoryPort messageRepository;
    private final AgentEngine agentEngine;
    private final int resumeMaxTurns;

    public ApprovalResumeService(ApprovalRepositoryPort approvalRepository,
                                 RunRepositoryPort runRepository,
                                 ToolExecutionRepositoryPort toolExecutionRepository,
                                 ToolRegistry toolRegistry,
                                 ApprovalResumeLockRegistry lockRegistry,
                                 Clock clock) {
        this(approvalRepository, runRepository, toolExecutionRepository, toolRegistry, lockRegistry, clock,
            null, null, null, 20);
    }

    public ApprovalResumeService(ApprovalRepositoryPort approvalRepository,
                                 RunRepositoryPort runRepository,
                                 ToolExecutionRepositoryPort toolExecutionRepository,
                                 ToolRegistry toolRegistry,
                                 ApprovalResumeLockRegistry lockRegistry,
                                 Clock clock,
                                 SessionService sessionService,
                                 MessageRepositoryPort messageRepository,
                                 AgentEngine agentEngine) {
        this(approvalRepository, runRepository, toolExecutionRepository, toolRegistry, lockRegistry, clock,
            sessionService, messageRepository, agentEngine, 20);
    }

    public ApprovalResumeService(ApprovalRepositoryPort approvalRepository,
                                 RunRepositoryPort runRepository,
                                 ToolExecutionRepositoryPort toolExecutionRepository,
                                 ToolRegistry toolRegistry,
                                 ApprovalResumeLockRegistry lockRegistry,
                                 Clock clock,
                                 SessionService sessionService,
                                 MessageRepositoryPort messageRepository,
                                 AgentEngine agentEngine,
                                 int resumeMaxTurns) {
        this.approvalRepository = DomainGuards.requireNonNull(approvalRepository, "approvalRepository");
        this.runRepository = DomainGuards.requireNonNull(runRepository, "runRepository");
        this.toolExecutionRepository = DomainGuards.requireNonNull(toolExecutionRepository, "toolExecutionRepository");
        this.toolRegistry = DomainGuards.requireNonNull(toolRegistry, "toolRegistry");
        this.lockRegistry = DomainGuards.requireNonNull(lockRegistry, "lockRegistry");
        this.clock = DomainGuards.requireNonNull(clock, "clock");
        this.sessionService = sessionService;
        this.messageRepository = messageRepository;
        this.agentEngine = agentEngine;
        this.resumeMaxTurns = Math.max(resumeMaxTurns, 1);
    }

    /**
     * Resume the tool call associated with the given approval.
     *
     * <p>The entire resume flow is guarded by a per-approvalId lock so that
     * concurrent calls for the same approval are serialised. The approval state
     * is re-read inside the lock to avoid acting on stale data.</p>
     *
     * @param approvalId the approval ID to resume
     * @return the resume result; never null
     */
    public ApprovalResumeResult resume(String approvalId) {
        DomainGuards.requireNonBlank(approvalId, "approvalId");

        return lockRegistry.withLock(approvalId, () -> doResume(approvalId));
    }

    private ApprovalResumeResult doResume(String approvalId) {
        Optional<ApprovalRequest> maybeApproval = approvalRepository.findById(approvalId);
        if (maybeApproval.isEmpty()) {
            return failedResult(approvalId, null, null, null,
                "Approval not found: " + approvalId);
        }

        ApprovalRequest approval = maybeApproval.get();
        if (approval.status() != ApprovalStatus.APPROVED) {
            return failedResult(approvalId, approval.runId(), approval.toolCallId(), approval.toolName(),
                "Approval is not approved: " + approval.status());
        }

        Instant now = clock.instant();
        boolean claimed = approvalRepository.claimForResume(approvalId, now);
        if (!claimed) {
            ApprovalRequest current = approvalRepository.findById(approvalId).orElse(approval);
            return failedResult(approvalId, current.runId(), current.toolCallId(), current.toolName(),
                "Approval already claimed or resumed: " + current.status());
        }

        // Re-read after successful claim; status should be RESUMING
        ApprovalRequest claimedApproval = approvalRepository.findById(approvalId).orElseThrow();

        Optional<AgentRunSummary> maybeRun = runRepository.findById(claimedApproval.runId());
        if (maybeRun.isEmpty()) {
            consumeClaimedApproval(claimedApproval,
                "resume failed before tool execution: run not found");
            return failedResult(approvalId, claimedApproval.runId(), claimedApproval.toolCallId(),
                claimedApproval.toolName(),
                "Run not found: " + claimedApproval.runId());
        }

        Optional<Session> maybeSession = runRepository.findSessionById(claimedApproval.sessionId());
        if (maybeSession.isEmpty()) {
            consumeClaimedApproval(claimedApproval,
                "resume failed before tool execution: session not found");
            return failedResult(approvalId, claimedApproval.runId(), claimedApproval.toolCallId(),
                claimedApproval.toolName(),
                "Session not found: " + claimedApproval.sessionId());
        }

        AgentRunSummary runSummary = maybeRun.get();
        Session session = maybeSession.get();

        Optional<ToolExecutionRecord> maybeOriginal = findOriginalToolExecution(claimedApproval.runId(), claimedApproval.toolCallId());
        if (maybeOriginal.isEmpty()) {
            consumeClaimedApproval(claimedApproval,
                "resume failed before tool execution: original tool execution not found");
            return failedResult(approvalId, claimedApproval.runId(), claimedApproval.toolCallId(),
                claimedApproval.toolName(),
                "Original tool execution not found for runId=" + claimedApproval.runId()
                    + " toolCallId=" + claimedApproval.toolCallId());
        }

        ToolExecutionRecord original = maybeOriginal.get();
        ToolCall call = ToolCall.of(original.stepId(), original.toolName(), original.argumentsJson());
        ToolExecutionContext context = new ToolExecutionContext(
            Path.of(session.workDir()),
            claimedApproval.runId(),
            claimedApproval.sessionId(),
            claimedApproval.id()
        );

        Instant startedAt = clock.instant();
        ToolResult toolResult = toolRegistry.execute(call, context);
        Instant completedAt = clock.instant();

        // Append audit record for the resume attempt
        ToolExecutionRecord resumedRecord = new ToolExecutionRecord(
            UUID.randomUUID().toString(),
            claimedApproval.runId(),
            claimedApproval.sessionId(),
            original.stepId(),
            original.toolName(),
            original.argumentsJson(),
            toolResult.output(),
            toolResult.error(),
            startedAt,
            completedAt
        );
        toolExecutionRepository.append(claimedApproval.runId(), resumedRecord);

        if (toolResult.error()) {
            String resumeReason = "resume attempted but tool failed";
            ApprovalRequest consumed = claimedApproval.markResumed(resumeReason, completedAt);
            approvalRepository.update(consumed);
            String failureReason = "Resume failed: " + toolResult.output();
            runRepository.saveRunFailed(claimedApproval.runId(), runSummary.turnCount(), failureReason, completedAt);
            return new ApprovalResumeResult(
                approvalId,
                claimedApproval.runId(),
                claimedApproval.toolCallId(),
                claimedApproval.toolName(),
                true,
                true,
                AgentRunStatus.FAILED,
                toolResult.output(),
                ""
            );
        }

        // Append the tool observation to the session so the LLM can see it
        Message observation = Message.toolObservation(call.id(), toolResult.output());
        if (sessionService != null) {
            sessionService.appendMessage(session.id(), observation);
        }
        if (messageRepository != null) {
            messageRepository.append(claimedApproval.runId(), session.id(), observation);
        }

        // Continue the ReAct loop if an engine is available
        AgentRunResult engineResult = null;
        if (agentEngine != null) {
            AgentRun run = reconstructWaitingRun(runSummary);
            ToolExecutionContext continuationContext = new ToolExecutionContext(
                Path.of(session.workDir()),
                claimedApproval.runId(),
                claimedApproval.sessionId()
            );
            engineResult = agentEngine.resume(run, session, continuationContext, toolExecutionRepository);
        }

        String resumeReason = engineResult != null && engineResult.success()
            ? "resumed and run continued to completion"
            : "resumed successfully";
        ApprovalRequest consumed = claimedApproval.markResumed(resumeReason, completedAt);
        approvalRepository.update(consumed);

        if (engineResult == null) {
            runRepository.saveRunCompleted(claimedApproval.runId(), runSummary.turnCount(), completedAt);
            return new ApprovalResumeResult(
                approvalId,
                claimedApproval.runId(),
                claimedApproval.toolCallId(),
                claimedApproval.toolName(),
                true,
                false,
                AgentRunStatus.COMPLETED,
                toolResult.output(),
                ""
            );
        }

        if (engineResult.waitingForApproval()) {
            String nextApprovalId = extractApprovalId(engineResult);
            runRepository.saveRunWaitingForApproval(
                claimedApproval.runId(), engineResult.turnCount(), nextApprovalId, completedAt);
            return new ApprovalResumeResult(
                approvalId,
                claimedApproval.runId(),
                claimedApproval.toolCallId(),
                claimedApproval.toolName(),
                true,
                false,
                AgentRunStatus.WAITING_APPROVAL,
                toolResult.output(),
                engineResult.errorReason()
            );
        }

        if (engineResult.success()) {
            runRepository.saveRunCompleted(claimedApproval.runId(), engineResult.turnCount(), completedAt);
        } else {
            runRepository.saveRunFailed(claimedApproval.runId(), engineResult.turnCount(),
                engineResult.errorReason(), completedAt);
        }

        return new ApprovalResumeResult(
            approvalId,
            claimedApproval.runId(),
            claimedApproval.toolCallId(),
            claimedApproval.toolName(),
            true,
            !engineResult.success(),
            engineResult.success() ? AgentRunStatus.COMPLETED : AgentRunStatus.FAILED,
            toolResult.output(),
            engineResult.finalMessage()
        );
    }

    private String extractApprovalId(AgentRunResult result) {
        String reason = result.errorReason();
        if (reason == null || !reason.startsWith("Approval required: ")) {
            return "";
        }
        String remainder = reason.substring("Approval required: ".length());
        int spaceIdx = remainder.indexOf(' ');
        return spaceIdx > 0 ? remainder.substring(0, spaceIdx) : remainder;
    }

    private AgentRun reconstructWaitingRun(AgentRunSummary runSummary) {
        int maxTurns = Math.max(resumeMaxTurns, runSummary.turnCount() + 1);
        AgentRun run = AgentRun.start(runSummary.id(), runSummary.sessionId(), maxTurns, runSummary.startedAt());
        for (int i = 0; i < runSummary.turnCount(); i++) {
            run = run.nextTurn();
        }
        return run.waitForApproval();
    }

    private void consumeClaimedApproval(ApprovalRequest claimedApproval, String reason) {
        ApprovalRequest consumed = claimedApproval.markResumed(reason, clock.instant());
        approvalRepository.update(consumed);
    }

    private Optional<ToolExecutionRecord> findOriginalToolExecution(String runId, String toolCallId) {
        List<ToolExecutionRecord> records = toolExecutionRepository.findByRunId(runId);
        return records.stream()
            .filter(r -> r.stepId().equals(toolCallId))
            .findFirst();
    }

    private ApprovalResumeResult failedResult(String approvalId,
                                              String runId,
                                              String toolCallId,
                                              String toolName,
                                              String error) {
        return new ApprovalResumeResult(
            approvalId,
            runId != null ? runId : "",
            toolCallId != null ? toolCallId : "",
            toolName != null ? toolName : "",
            false,
            true,
            AgentRunStatus.FAILED,
            error,
            ""
        );
    }
}

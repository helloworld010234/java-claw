package com.tinyclaw.application.approval;

import com.tinyclaw.application.persistence.AgentRunSummary;
import com.tinyclaw.application.persistence.ToolExecutionRecord;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.domain.run.AgentRunStatus;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
import com.tinyclaw.ports.persistence.RunRepositoryPort;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import com.tinyclaw.ports.tool.ToolExecutionContext;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service that resumes a previously approved tool call.
 *
 * <p>Looks up the approval, the original run, and the original tool execution
 * audit record, then re-executes the same tool call. The new execution result
 * is appended as a fresh audit record and the run status is updated.</p>
 *
 * <p><strong>Single-use semantics:</strong> once a tool call has been executed
 * (whether it succeeds or fails), the approval is marked {@code RESUMED}
 * and can never be resumed again. This prevents replay attacks on side-effect
 * tools such as {@code shell_command} or {@code write_file}.</p>
 */
public class ApprovalResumeService {

    private final ApprovalRepositoryPort approvalRepository;
    private final RunRepositoryPort runRepository;
    private final ToolExecutionRepositoryPort toolExecutionRepository;
    private final ToolRegistry toolRegistry;
    private final ApprovalResumeLockRegistry lockRegistry;
    private final Clock clock;

    public ApprovalResumeService(ApprovalRepositoryPort approvalRepository,
                                 RunRepositoryPort runRepository,
                                 ToolExecutionRepositoryPort toolExecutionRepository,
                                 ToolRegistry toolRegistry,
                                 ApprovalResumeLockRegistry lockRegistry,
                                 Clock clock) {
        this.approvalRepository = DomainGuards.requireNonNull(approvalRepository, "approvalRepository");
        this.runRepository = DomainGuards.requireNonNull(runRepository, "runRepository");
        this.toolExecutionRepository = DomainGuards.requireNonNull(toolExecutionRepository, "toolExecutionRepository");
        this.toolRegistry = DomainGuards.requireNonNull(toolRegistry, "toolRegistry");
        this.lockRegistry = DomainGuards.requireNonNull(lockRegistry, "lockRegistry");
        this.clock = DomainGuards.requireNonNull(clock, "clock");
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

        AgentRunSummary run = maybeRun.get();
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

        // Consume the approval token regardless of tool success or failure
        String resumeReason = toolResult.error()
            ? "resume attempted but tool failed"
            : "resumed successfully";
        ApprovalRequest consumed = claimedApproval.markResumed(resumeReason, completedAt);
        approvalRepository.update(consumed);

        if (toolResult.error()) {
            runRepository.saveRunFailed(claimedApproval.runId(), run.turnCount(),
                "Resume failed: " + toolResult.output(), completedAt);
            return new ApprovalResumeResult(
                approvalId,
                claimedApproval.runId(),
                claimedApproval.toolCallId(),
                claimedApproval.toolName(),
                true,
                true,
                AgentRunStatus.FAILED,
                toolResult.output()
            );
        }

        runRepository.saveRunCompleted(claimedApproval.runId(), run.turnCount(), completedAt);
        return new ApprovalResumeResult(
            approvalId,
            claimedApproval.runId(),
            claimedApproval.toolCallId(),
            claimedApproval.toolName(),
            true,
            false,
            AgentRunStatus.COMPLETED,
            toolResult.output()
        );
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
            error
        );
    }
}

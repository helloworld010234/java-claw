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
 */
public class ApprovalResumeService {

    private final ApprovalRepositoryPort approvalRepository;
    private final RunRepositoryPort runRepository;
    private final ToolExecutionRepositoryPort toolExecutionRepository;
    private final ToolRegistry toolRegistry;
    private final Clock clock;

    public ApprovalResumeService(ApprovalRepositoryPort approvalRepository,
                                 RunRepositoryPort runRepository,
                                 ToolExecutionRepositoryPort toolExecutionRepository,
                                 ToolRegistry toolRegistry,
                                 Clock clock) {
        this.approvalRepository = DomainGuards.requireNonNull(approvalRepository, "approvalRepository");
        this.runRepository = DomainGuards.requireNonNull(runRepository, "runRepository");
        this.toolExecutionRepository = DomainGuards.requireNonNull(toolExecutionRepository, "toolExecutionRepository");
        this.toolRegistry = DomainGuards.requireNonNull(toolRegistry, "toolRegistry");
        this.clock = DomainGuards.requireNonNull(clock, "clock");
    }

    /**
     * Resume the tool call associated with the given approval.
     *
     * @param approvalId the approval ID to resume
     * @return the resume result; never null
     */
    public ApprovalResumeResult resume(String approvalId) {
        DomainGuards.requireNonBlank(approvalId, "approvalId");

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

        Optional<AgentRunSummary> maybeRun = runRepository.findById(approval.runId());
        if (maybeRun.isEmpty()) {
            return failedResult(approvalId, approval.runId(), approval.toolCallId(), approval.toolName(),
                "Run not found: " + approval.runId());
        }

        Optional<Session> maybeSession = runRepository.findSessionById(approval.sessionId());
        if (maybeSession.isEmpty()) {
            return failedResult(approvalId, approval.runId(), approval.toolCallId(), approval.toolName(),
                "Session not found: " + approval.sessionId());
        }

        AgentRunSummary run = maybeRun.get();
        Session session = maybeSession.get();

        Optional<ToolExecutionRecord> maybeOriginal = findOriginalToolExecution(approval.runId(), approval.toolCallId());
        if (maybeOriginal.isEmpty()) {
            return failedResult(approvalId, approval.runId(), approval.toolCallId(), approval.toolName(),
                "Original tool execution not found for runId=" + approval.runId()
                    + " toolCallId=" + approval.toolCallId());
        }

        ToolExecutionRecord original = maybeOriginal.get();
        ToolCall call = ToolCall.of(original.stepId(), original.toolName(), original.argumentsJson());
        ToolExecutionContext context = new ToolExecutionContext(
            Path.of(session.workDir()),
            approval.runId(),
            approval.sessionId(),
            approval.id()
        );

        Instant startedAt = clock.instant();
        ToolResult toolResult = toolRegistry.execute(call, context);
        Instant completedAt = clock.instant();

        ToolExecutionRecord resumedRecord = new ToolExecutionRecord(
            UUID.randomUUID().toString(),
            approval.runId(),
            approval.sessionId(),
            original.stepId(),
            original.toolName(),
            original.argumentsJson(),
            toolResult.output(),
            toolResult.error(),
            startedAt,
            completedAt
        );
        toolExecutionRepository.append(approval.runId(), resumedRecord);

        if (toolResult.error()) {
            runRepository.saveRunFailed(approval.runId(), run.turnCount(),
                "Resume failed: " + toolResult.output(), completedAt);
            return new ApprovalResumeResult(
                approvalId,
                approval.runId(),
                approval.toolCallId(),
                approval.toolName(),
                true,
                true,
                AgentRunStatus.FAILED,
                toolResult.output()
            );
        }

        runRepository.saveRunCompleted(approval.runId(), run.turnCount(), completedAt);
        return new ApprovalResumeResult(
            approvalId,
            approval.runId(),
            approval.toolCallId(),
            approval.toolName(),
            true,
            false,
            AgentRunStatus.COMPLETED,
            toolResult.output()
        );
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

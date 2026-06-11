package com.tinyclaw.application.approval;

import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import com.tinyclaw.ports.tool.ToolExecutionDecision;
import com.tinyclaw.ports.tool.ToolExecutionPolicy;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Policy that requires human approval for configured tools.
 *
 * <p>When a tool call matches {@code required-tools}:</p>
 * <ul>
 *   <li>If run/session context is missing, denies the call.</li>
 *   <li>If the context carries an {@code approvedApprovalId} and the approval
 *       exists with status APPROVED and matches runId, toolCallId and toolName,
 *       the call is allowed.</li>
 *   <li>If a pending approval already exists for the same run + toolCallId, reuses it.</li>
 *   <li>Otherwise creates a new pending approval request and requires approval.</li>
 * </ul>
 */
public class ApprovalGatePolicy implements ToolExecutionPolicy {

    private final ApprovalRepositoryPort approvalRepository;
    private final List<String> requiredTools;
    private final ApprovalArgumentPreviewer previewer;
    private final Clock clock;
    private final boolean enabled;

    public ApprovalGatePolicy(ApprovalRepositoryPort approvalRepository,
                              List<String> requiredTools,
                              Clock clock) {
        this(approvalRepository, requiredTools, clock, true);
    }

    public ApprovalGatePolicy(ApprovalRepositoryPort approvalRepository,
                              List<String> requiredTools,
                              Clock clock,
                              boolean enabled) {
        this.approvalRepository = approvalRepository;
        this.requiredTools = requiredTools != null ? List.copyOf(requiredTools) : List.of();
        this.previewer = new ApprovalArgumentPreviewer();
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.enabled = enabled;
    }

    @Override
    public ToolExecutionDecision decide(ToolCall call, ToolExecutionContext context) {
        if (!enabled) {
            return ToolExecutionDecision.allow();
        }

        if (!requiredTools.contains(call.name())) {
            return ToolExecutionDecision.allow();
        }

        if (context.runId() == null || context.runId().isBlank()
            || context.sessionId() == null || context.sessionId().isBlank()) {
            return ToolExecutionDecision.deny(
                "Approval gate requires run and session context"
            );
        }

        if (context.approvedApprovalId() != null && !context.approvedApprovalId().isBlank()) {
            return evaluateApprovedApproval(call, context);
        }

        Optional<ApprovalRequest> existing = approvalRepository.findByRunIdAndToolCallId(
            context.runId(), call.id()
        );
        if (existing.isPresent() && existing.get().status() == ApprovalStatus.PENDING) {
            return ToolExecutionDecision.requireApproval(
                "Approval required: " + existing.get().id()
            );
        }

        String preview = previewer.preview(call.argumentsJson());
        ApprovalRequest request = ApprovalRequest.pending(
            UUID.randomUUID().toString(),
            context.runId(),
            context.sessionId(),
            call.id(),
            call.name(),
            preview,
            clock.instant()
        );
        approvalRepository.save(request);

        return ToolExecutionDecision.requireApproval(
            "Approval required: " + request.id()
        );
    }

    private ToolExecutionDecision evaluateApprovedApproval(ToolCall call, ToolExecutionContext context) {
        Optional<ApprovalRequest> maybeApproval = approvalRepository.findById(context.approvedApprovalId());
        if (maybeApproval.isEmpty()) {
            return ToolExecutionDecision.deny(
                "Approved approval not found: " + context.approvedApprovalId()
            );
        }

        ApprovalRequest approval = maybeApproval.get();
        if (approval.status() != ApprovalStatus.APPROVED && approval.status() != ApprovalStatus.RESUMING) {
            return ToolExecutionDecision.deny(
                "Approval is not approved: " + approval.status()
            );
        }

        if (!approval.runId().equals(context.runId())
            || !approval.toolCallId().equals(call.id())
            || !approval.toolName().equals(call.name())) {
            return ToolExecutionDecision.deny(
                "Approval does not match run, toolCallId or toolName"
            );
        }

        return ToolExecutionDecision.allow();
    }
}

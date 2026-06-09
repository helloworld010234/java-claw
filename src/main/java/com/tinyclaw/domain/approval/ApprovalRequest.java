package com.tinyclaw.domain.approval;

import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.common.TinyClawDomainException;

import java.time.Instant;

/**
 * 高危操作审批请求。
 *
 * <p>不可变状态机。状态转换通过返回新实例实现。</p>
 */
public final class ApprovalRequest {

    private final String id;
    private final String runId;
    private final String sessionId;
    private final String toolCallId;
    private final String toolName;
    private final String argumentsPreview;
    private final ApprovalStatus status;
    private final String decisionReason;
    private final Instant requestedAt;
    private final Instant decidedAt;

    private ApprovalRequest(String id, String runId, String sessionId, String toolCallId, String toolName,
                            String argumentsPreview, ApprovalStatus status, String decisionReason,
                            Instant requestedAt, Instant decidedAt) {
        this.id = DomainGuards.requireNonBlank(id, "id");
        this.runId = DomainGuards.requireNonBlank(runId, "runId");
        this.sessionId = DomainGuards.requireNonBlank(sessionId, "sessionId");
        this.toolCallId = DomainGuards.requireNonBlank(toolCallId, "toolCallId");
        this.toolName = DomainGuards.requireNonBlank(toolName, "toolName");
        this.argumentsPreview = argumentsPreview != null ? argumentsPreview : "";
        this.status = DomainGuards.requireNonNull(status, "status");
        this.decisionReason = decisionReason;
        this.requestedAt = DomainGuards.requireNonNull(requestedAt, "requestedAt");
        this.decidedAt = decidedAt;
        if (decidedAt != null && decidedAt.isBefore(requestedAt)) {
            throw new TinyClawDomainException("decidedAt must not be before requestedAt");
        }
    }

    public String id() {
        return id;
    }

    public String runId() {
        return runId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String toolCallId() {
        return toolCallId;
    }

    public String toolName() {
        return toolName;
    }

    public String argumentsPreview() {
        return argumentsPreview;
    }

    public ApprovalStatus status() {
        return status;
    }

    public String decisionReason() {
        return decisionReason;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public Instant decidedAt() {
        return decidedAt;
    }

    /**
     * 创建待审批请求。
     */
    public static ApprovalRequest pending(String id, String runId, String sessionId, String toolCallId,
                                          String toolName, String argumentsPreview, Instant requestedAt) {
        DomainGuards.requireNonNull(requestedAt, "requestedAt");
        return new ApprovalRequest(id, runId, sessionId, toolCallId, toolName, argumentsPreview,
                ApprovalStatus.PENDING, null, requestedAt, null);
    }

    /**
     * 审批通过。
     *
     * @param reason 决策理由，必须非空
     */
    public ApprovalRequest approve(String reason, Instant now) {
        DomainGuards.requireNonBlank(reason, "reason");
        DomainGuards.requireNonNull(now, "now");
        assertPending("approve");
        assertDecidedAtNotBeforeRequestedAt(now);
        return new ApprovalRequest(id, runId, sessionId, toolCallId, toolName, argumentsPreview,
                ApprovalStatus.APPROVED, reason, requestedAt, now);
    }

    /**
     * 审批拒绝。
     *
     * @param reason 决策理由，必须非空
     */
    public ApprovalRequest reject(String reason, Instant now) {
        DomainGuards.requireNonBlank(reason, "reason");
        DomainGuards.requireNonNull(now, "now");
        assertPending("reject");
        assertDecidedAtNotBeforeRequestedAt(now);
        return new ApprovalRequest(id, runId, sessionId, toolCallId, toolName, argumentsPreview,
                ApprovalStatus.REJECTED, reason, requestedAt, now);
    }

    /**
     * 审批超时过期。
     */
    public ApprovalRequest expire(Instant now) {
        DomainGuards.requireNonNull(now, "now");
        assertPending("expire");
        assertDecidedAtNotBeforeRequestedAt(now);
        return new ApprovalRequest(id, runId, sessionId, toolCallId, toolName, argumentsPreview,
                ApprovalStatus.EXPIRED, null, requestedAt, now);
    }

    private void assertPending(String operation) {
        if (status != ApprovalStatus.PENDING) {
            throw new TinyClawDomainException(
                    "Cannot perform '" + operation + "' on approval request in status: " + status);
        }
    }

    private void assertDecidedAtNotBeforeRequestedAt(Instant decidedAt) {
        if (decidedAt.isBefore(requestedAt)) {
            throw new TinyClawDomainException("decision time must not be before requestedAt");
        }
    }
}

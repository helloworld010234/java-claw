package com.tinyclaw.ports.persistence;

import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port for persisting and retrieving approval requests.
 */
public interface ApprovalRepositoryPort {

    /**
     * Save a new approval request.
     */
    void save(ApprovalRequest request);

    /**
     * Find an approval request by its unique id.
     */
    Optional<ApprovalRequest> findById(String id);

    /**
     * Find an approval request by run id and tool call id.
     */
    Optional<ApprovalRequest> findByRunIdAndToolCallId(String runId, String toolCallId);

    /**
     * Find all approval requests for a given run.
     */
    List<ApprovalRequest> findByRunId(String runId);

    /**
     * Find all approval requests with the given status.
     */
    List<ApprovalRequest> findByStatus(ApprovalStatus status);

    /**
     * Find all approval requests.
     */
    List<ApprovalRequest> findAll();

    /**
     * Update an existing approval request (must already exist).
     */
    void update(ApprovalRequest request);

    /**
     * Atomically claim an APPROVED approval for resume.
     *
     * <p>Uses a single conditional UPDATE statement:
     * only succeeds if the current status is {@code APPROVED}.
     * On success the status transitions to {@code RESUMING}.</p>
     *
     * @param approvalId the approval ID to claim
     * @param now        the instant to record as decided_at and updated_at
     * @return {@code true} if the claim succeeded, {@code false} if the approval
     *         was not in APPROVED status (already claimed, resumed, or never approved)
     */
    boolean claimForResume(String approvalId, Instant now);
}

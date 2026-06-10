package com.tinyclaw.ports.persistence;

import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;

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
}

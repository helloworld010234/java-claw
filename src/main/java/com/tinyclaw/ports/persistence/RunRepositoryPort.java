package com.tinyclaw.ports.persistence;

import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.session.Session;

import java.time.Instant;
import java.util.Optional;

/**
 * Port for persisting and retrieving agent run audit records.
 */
public interface RunRepositoryPort {

    /**
     * Save a session record.
     */
    void saveSession(Session session);

    /**
     * Find a session by its id.
     *
     * @return empty if not found
     */
    Optional<Session> findSessionById(String sessionId);

    /**
     * Save a newly started run with mode and prompt.
     */
    void saveRunStarted(AgentRun run, String mode, String prompt);

    /**
     * Mark a run as completed.
     */
    void saveRunCompleted(AgentRun run);

    /**
     * Mark a run as completed with explicit turn count.
     */
    void saveRunCompleted(String runId, int turnCount, Instant completedAt);

    /**
     * Mark a run as failed with a reason.
     */
    void saveRunFailed(AgentRun run, String reason);

    /**
     * Mark a run as failed with explicit turn count.
     */
    void saveRunFailed(String runId, int turnCount, String reason, Instant completedAt);

    /**
     * Mark a run as waiting for human approval.
     *
     * @param runId       the run identifier
     * @param turnCount   the current turn when the run paused
     * @param approvalId  the approval request identifier
     * @param now         the pause timestamp
     */
    default void saveRunWaitingForApproval(String runId, int turnCount, String approvalId, Instant now) {
        // no-op by default; test stubs may override
    }

    /**
     * Find a run summary by id.
     *
     * @return empty if not found
     */
    Optional<AgentRunSummary> findById(String runId);
}

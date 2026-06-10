package com.tinyclaw.ports.persistence;

import com.tinyclaw.application.persistence.AgentRunSummary;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.session.Session;

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
     * Save a newly started run with mode and prompt.
     */
    void saveRunStarted(AgentRun run, String mode, String prompt);

    /**
     * Mark a run as completed.
     */
    void saveRunCompleted(AgentRun run);

    /**
     * Mark a run as failed with a reason.
     */
    void saveRunFailed(AgentRun run, String reason);

    /**
     * Find a run summary by id.
     *
     * @return empty if not found
     */
    Optional<AgentRunSummary> findById(String runId);
}

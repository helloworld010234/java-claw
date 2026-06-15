package com.tinyclaw.adapters.persistence;

import com.tinyclaw.ports.persistence.AgentRunSummary;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.run.AgentRunStatus;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.domain.session.SessionStatus;
import com.tinyclaw.ports.persistence.RunRepositoryPort;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of {@link RunRepositoryPort} using Spring Data JDBC.
 */
@Repository
public class JdbcRunRepository implements RunRepositoryPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void saveSession(Session session) {
        String updateSql = """
            UPDATE agent_sessions
            SET workspace_path = ?, status = ?, updated_at = ?
            WHERE id = ?
            """;
        int updated = jdbcTemplate.update(updateSql,
            session.workDir(),
            session.status().name().toLowerCase(),
            Timestamp.from(session.updatedAt()),
            session.id()
        );
        if (updated == 0) {
            String insertSql = """
                INSERT INTO agent_sessions (id, workspace_path, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """;
            jdbcTemplate.update(insertSql,
                session.id(),
                session.workDir(),
                session.status().name().toLowerCase(),
                Timestamp.from(session.createdAt()),
                Timestamp.from(session.updatedAt())
            );
        }
    }

    @Override
    public Optional<Session> findSessionById(String sessionId) {
        String sql = """
            SELECT id, workspace_path, status, created_at, updated_at
            FROM agent_sessions
            WHERE id = ?
            """;
        try {
            Session session = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
                String statusStr = rs.getString("status");
                SessionStatus status = SessionStatus.valueOf(statusStr.toUpperCase());
                return Session.reconstruct(
                    rs.getString("id"),
                    rs.getString("workspace_path"),
                    status,
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()
                );
            }, sessionId);
            return Optional.ofNullable(session);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void saveRunStarted(AgentRun run, String mode, String prompt) {
        String sql = """
            INSERT INTO agent_runs (id, session_id, status, max_turns, current_turn, error_reason, started_at, mode, prompt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        jdbcTemplate.update(sql,
            run.id(),
            run.sessionId(),
            run.status().name().toLowerCase(),
            run.maxTurns(),
            run.currentTurn(),
            run.errorReason(),
            Timestamp.from(run.startedAt()),
            mode,
            prompt
        );
    }

    @Override
    public void saveRunCompleted(AgentRun run) {
        saveRunCompleted(run.id(), run.currentTurn(), run.endedAt());
    }

    @Override
    public void saveRunCompleted(String runId, int turnCount, Instant completedAt) {
        String sql = """
            UPDATE agent_runs
            SET status = ?, current_turn = ?, ended_at = ?, completed_at = ?
            WHERE id = ?
            """;
        jdbcTemplate.update(sql,
            AgentRunStatus.COMPLETED.name().toLowerCase(),
            turnCount,
            completedAt != null ? Timestamp.from(completedAt) : null,
            completedAt != null ? Timestamp.from(completedAt) : null,
            runId
        );
    }

    @Override
    public void saveRunFailed(AgentRun run, String reason) {
        saveRunFailed(run.id(), run.currentTurn(), reason, run.endedAt());
    }

    @Override
    public void saveRunFailed(String runId, int turnCount, String reason, Instant completedAt) {
        String sql = """
            UPDATE agent_runs
            SET status = ?, current_turn = ?, error_reason = ?, ended_at = ?, completed_at = ?
            WHERE id = ?
            """;
        jdbcTemplate.update(sql,
            AgentRunStatus.FAILED.name().toLowerCase(),
            turnCount,
            reason,
            completedAt != null ? Timestamp.from(completedAt) : null,
            completedAt != null ? Timestamp.from(completedAt) : null,
            runId
        );
    }

    @Override
    public void saveRunWaitingForApproval(String runId, int turnCount, String approvalId, Instant now) {
        String sql = """
            UPDATE agent_runs
            SET status = ?, current_turn = ?, approval_id = ?, updated_at = ?
            WHERE id = ?
            """;
        jdbcTemplate.update(sql,
            AgentRunStatus.WAITING_APPROVAL.name().toLowerCase(),
            turnCount,
            approvalId,
            Timestamp.from(now),
            runId
        );
    }

    @Override
    public Optional<AgentRunSummary> findById(String runId) {
        String sql = """
            SELECT id, session_id, mode, status, current_turn, prompt, error_reason, started_at, ended_at, approval_id
            FROM agent_runs
            WHERE id = ?
            """;
        try {
            AgentRunSummary summary = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
                String statusStr = rs.getString("status");
                AgentRunStatus status = AgentRunStatus.valueOf(statusStr.toUpperCase());
                Timestamp endedAt = rs.getTimestamp("ended_at");
                return new AgentRunSummary(
                    rs.getString("id"),
                    rs.getString("session_id"),
                    rs.getString("mode"),
                    status,
                    rs.getInt("current_turn"),
                    rs.getString("prompt"),
                    rs.getString("error_reason"),
                    rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toInstant() : null,
                    endedAt != null ? endedAt.toInstant() : null,
                    rs.getString("approval_id")
                );
            }, runId);
            return Optional.ofNullable(summary);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}

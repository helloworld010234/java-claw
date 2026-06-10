package com.tinyclaw.adapters.persistence;

import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC implementation of {@link ApprovalRepositoryPort}.
 */
@Repository
public class JdbcApprovalRepository implements ApprovalRepositoryPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcApprovalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(ApprovalRequest request) {
        String sql = """
            INSERT INTO approval_requests
            (id, run_id, session_id, tool_call_id, tool_name, arguments_preview, status,
             decision_reason, requested_at, decided_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        Instant now = Instant.now();
        jdbcTemplate.update(sql,
            request.id(),
            request.runId(),
            request.sessionId(),
            request.toolCallId(),
            request.toolName(),
            request.argumentsPreview(),
            request.status().name(),
            request.decisionReason(),
            Timestamp.from(request.requestedAt()),
            request.decidedAt() != null ? Timestamp.from(request.decidedAt()) : null,
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }

    @Override
    public Optional<ApprovalRequest> findById(String id) {
        String sql = """
            SELECT id, run_id, session_id, tool_call_id, tool_name, arguments_preview,
                   status, decision_reason, requested_at, decided_at
            FROM approval_requests
            WHERE id = ?
            """;
        try {
            ApprovalRequest request = jdbcTemplate.queryForObject(sql, this::mapRow, id);
            return Optional.ofNullable(request);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<ApprovalRequest> findByRunIdAndToolCallId(String runId, String toolCallId) {
        String sql = """
            SELECT id, run_id, session_id, tool_call_id, tool_name, arguments_preview,
                   status, decision_reason, requested_at, decided_at
            FROM approval_requests
            WHERE run_id = ? AND tool_call_id = ?
            ORDER BY created_at DESC
            """;
        List<ApprovalRequest> results = jdbcTemplate.query(sql, this::mapRow, runId, toolCallId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<ApprovalRequest> findByRunId(String runId) {
        String sql = """
            SELECT id, run_id, session_id, tool_call_id, tool_name, arguments_preview,
                   status, decision_reason, requested_at, decided_at
            FROM approval_requests
            WHERE run_id = ?
            ORDER BY created_at
            """;
        return jdbcTemplate.query(sql, this::mapRow, runId);
    }

    @Override
    public List<ApprovalRequest> findAll() {
        String sql = """
            SELECT id, run_id, session_id, tool_call_id, tool_name, arguments_preview,
                   status, decision_reason, requested_at, decided_at
            FROM approval_requests
            ORDER BY created_at
            """;
        return jdbcTemplate.query(sql, this::mapRow);
    }

    @Override
    public List<ApprovalRequest> findByStatus(ApprovalStatus status) {
        String sql = """
            SELECT id, run_id, session_id, tool_call_id, tool_name, arguments_preview,
                   status, decision_reason, requested_at, decided_at
            FROM approval_requests
            WHERE status = ?
            ORDER BY created_at
            """;
        return jdbcTemplate.query(sql, this::mapRow, status.name());
    }

    @Override
    public void update(ApprovalRequest request) {
        String sql = """
            UPDATE approval_requests
            SET status = ?, decision_reason = ?, decided_at = ?, updated_at = ?
            WHERE id = ?
            """;
        jdbcTemplate.update(sql,
            request.status().name(),
            request.decisionReason(),
            request.decidedAt() != null ? Timestamp.from(request.decidedAt()) : null,
            Timestamp.from(Instant.now()),
            request.id()
        );
    }

    private ApprovalRequest mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String statusStr = rs.getString("status");
        ApprovalStatus status = ApprovalStatus.valueOf(statusStr);
        Timestamp requestedAt = rs.getTimestamp("requested_at");
        Timestamp decidedAt = rs.getTimestamp("decided_at");

        return new ApprovalRequest(
            rs.getString("id"),
            rs.getString("run_id"),
            rs.getString("session_id"),
            rs.getString("tool_call_id"),
            rs.getString("tool_name"),
            rs.getString("arguments_preview"),
            status,
            rs.getString("decision_reason"),
            requestedAt != null ? requestedAt.toInstant() : null,
            decidedAt != null ? decidedAt.toInstant() : null
        );
    }
}

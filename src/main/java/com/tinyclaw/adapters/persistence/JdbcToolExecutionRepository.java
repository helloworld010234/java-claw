package com.tinyclaw.adapters.persistence;

import com.tinyclaw.ports.persistence.ToolExecutionRecord;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * JDBC implementation of {@link ToolExecutionRepositoryPort}.
 */
@Repository
public class JdbcToolExecutionRepository implements ToolExecutionRepositoryPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcToolExecutionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void append(String runId, ToolExecutionRecord record) {
        String sql = """
            INSERT INTO tool_executions
            (id, run_id, session_id, tool_name, tool_call_id, arguments, result, is_error, started_at, completed_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        jdbcTemplate.update(sql,
            record.id() != null ? record.id() : UUID.randomUUID().toString(),
            runId,
            record.sessionId(),
            record.toolName(),
            record.stepId(),
            record.argumentsJson(),
            record.output(),
            record.isError(),
            record.startedAt() != null ? Timestamp.from(record.startedAt()) : null,
            record.completedAt() != null ? Timestamp.from(record.completedAt()) : null,
            Timestamp.from(java.time.Instant.now())
        );
    }

    @Override
    public List<ToolExecutionRecord> findByRunId(String runId) {
        String sql = """
            SELECT id, run_id, session_id, tool_call_id, tool_name, arguments, result, is_error, started_at, completed_at
            FROM tool_executions
            WHERE run_id = ?
            ORDER BY created_at
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Timestamp startedAt = rs.getTimestamp("started_at");
            Timestamp completedAt = rs.getTimestamp("completed_at");
            return new ToolExecutionRecord(
                rs.getString("id"),
                rs.getString("run_id"),
                rs.getString("session_id"),
                rs.getString("tool_call_id"),
                rs.getString("tool_name"),
                rs.getString("arguments"),
                rs.getString("result"),
                rs.getBoolean("is_error"),
                startedAt != null ? startedAt.toInstant() : null,
                completedAt != null ? completedAt.toInstant() : null
            );
        }, runId);
    }
}

package com.tinyclaw.adapters.persistence;

import com.tinyclaw.ports.persistence.UsageRecord;
import com.tinyclaw.ports.persistence.UsageRepositoryPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * JDBC implementation of {@link UsageRepositoryPort} writing to {@code usage_records}.
 */
public class JdbcUsageRepository implements UsageRepositoryPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcUsageRepository(JdbcTemplate jdbcTemplate) {
        if (jdbcTemplate == null) {
            throw new IllegalArgumentException("jdbcTemplate must not be null");
        }
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(UsageRecord record) {
        jdbcTemplate.update(
            """
            INSERT INTO usage_records
            (run_id, session_id, model, prompt_tokens, completion_tokens, total_tokens, estimated_cost_cny, provider, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            record.runId(),
            record.sessionId(),
            record.model(),
            record.promptTokens(),
            record.completionTokens(),
            record.promptTokens() + record.completionTokens(),
            record.estimatedCostCny(),
            "spring-ai",
            java.sql.Timestamp.from(record.recordedAt())
        );
    }

    @Override
    public List<UsageRecord> findByRunId(String runId) {
        String sql = """
            SELECT run_id, session_id, model, prompt_tokens, completion_tokens,
                   estimated_cost_cny, created_at
            FROM usage_records
            WHERE run_id = ?
            ORDER BY created_at
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new UsageRecord(
            rs.getString("run_id"),
            rs.getString("session_id"),
            rs.getString("model"),
            rs.getInt("prompt_tokens"),
            rs.getInt("completion_tokens"),
            rs.getObject("estimated_cost_cny") != null ? rs.getDouble("estimated_cost_cny") : null,
            true,
            rs.getTimestamp("created_at").toInstant()
        ), runId);
    }
}

package com.tinyclaw.adapters.persistence;

import com.tinyclaw.application.persistence.UsageRecord;
import com.tinyclaw.ports.persistence.UsageRepositoryPort;
import org.springframework.jdbc.core.JdbcTemplate;

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
}

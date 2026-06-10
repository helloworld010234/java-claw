package com.tinyclaw.adapters.persistence;

import com.tinyclaw.application.persistence.AgentMessageDto;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.Role;
import com.tinyclaw.ports.persistence.MessageRepositoryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * JDBC implementation of {@link MessageRepositoryPort}.
 */
@Repository
public class JdbcMessageRepository implements MessageRepositoryPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void append(String runId, String sessionId, Message message) {
        String sql = """
            INSERT INTO agent_messages (id, session_id, run_id, role, content, tool_call_id, sequence_number, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
        int nextSeq = nextSequenceNumber(sessionId);
        jdbcTemplate.update(sql,
            UUID.randomUUID().toString(),
            sessionId,
            runId,
            message.role().name().toLowerCase(),
            message.content(),
            message.toolCallId(),
            nextSeq,
            Timestamp.from(java.time.Instant.now())
        );
    }

    @Override
    public List<AgentMessageDto> findByRunId(String runId) {
        String sql = """
            SELECT id, session_id, run_id, role, content, tool_call_id, created_at
            FROM agent_messages
            WHERE run_id = ?
            ORDER BY sequence_number, created_at
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String roleStr = rs.getString("role");
            Role role = Role.valueOf(roleStr.toUpperCase());
            Timestamp createdAt = rs.getTimestamp("created_at");
            return new AgentMessageDto(
                rs.getString("id"),
                rs.getString("run_id"),
                rs.getString("session_id"),
                role,
                rs.getString("content"),
                rs.getString("tool_call_id"),
                createdAt != null ? createdAt.toInstant() : null
            );
        }, runId);
    }

    private int nextSequenceNumber(String sessionId) {
        String sql = "SELECT COALESCE(MAX(sequence_number), 0) + 1 FROM agent_messages WHERE session_id = ?";
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class, sessionId);
        return result != null ? result : 1;
    }
}

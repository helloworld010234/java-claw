package com.tinyclaw.adapters.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.ports.persistence.AgentMessageDto;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.Role;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.ports.persistence.MessageRepositoryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * JDBC implementation of {@link MessageRepositoryPort}.
 */
@Repository
public class JdbcMessageRepository implements MessageRepositoryPort {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcMessageRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(String runId, String sessionId, Message message) {
        String sql = """
            INSERT INTO agent_messages (id, session_id, run_id, role, content, tool_calls, tool_call_id, sequence_number, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        int nextSeq = nextSequenceNumber(sessionId);
        String toolCallsJson = serializeToolCalls(message.toolCalls());
        jdbcTemplate.update(sql,
            UUID.randomUUID().toString(),
            sessionId,
            runId,
            message.role().name().toLowerCase(),
            message.content(),
            toolCallsJson,
            message.toolCallId(),
            nextSeq,
            Timestamp.from(java.time.Instant.now())
        );
    }

    @Override
    public List<AgentMessageDto> findByRunId(String runId) {
        String sql = """
            SELECT id, session_id, run_id, role, content, tool_calls, tool_call_id, sequence_number, created_at
            FROM agent_messages
            WHERE run_id = ?
            ORDER BY sequence_number, created_at
            """;
        return jdbcTemplate.query(sql, this::mapRow, runId);
    }

    @Override
    public List<AgentMessageDto> findBySessionId(String sessionId, int limit) {
        String baseSql = """
            SELECT id, run_id, session_id, role, content, tool_calls, tool_call_id, sequence_number, created_at
            FROM agent_messages
            WHERE session_id = ? AND role != 'system'
            """;
        if (limit > 0) {
            String sql = """
                SELECT * FROM (
                """ + baseSql + """
                    ORDER BY sequence_number DESC, created_at DESC
                    LIMIT ?
                ) sub
                ORDER BY sequence_number ASC, created_at ASC
                """;
            return jdbcTemplate.query(sql, this::mapRow, sessionId, limit);
        }
        String sql = baseSql + " ORDER BY sequence_number ASC, created_at ASC";
        return jdbcTemplate.query(sql, this::mapRow, sessionId);
    }

    private AgentMessageDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        String roleStr = rs.getString("role");
        Role role = Role.valueOf(roleStr.toUpperCase());
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new AgentMessageDto(
            rs.getString("id"),
            rs.getString("run_id"),
            rs.getString("session_id"),
            role,
            rs.getString("content"),
            rs.getString("tool_calls"),
            rs.getString("tool_call_id"),
            rs.getInt("sequence_number"),
            createdAt != null ? createdAt.toInstant() : null
        );
    }

    private String serializeToolCalls(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(toolCalls);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize tool_calls", e);
        }
    }

    private int nextSequenceNumber(String sessionId) {
        String sql = "SELECT COALESCE(MAX(sequence_number), 0) + 1 FROM agent_messages WHERE session_id = ?";
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class, sessionId);
        return result != null ? result : 1;
    }
}

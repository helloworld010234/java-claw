package com.tinyclaw.adapters.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.ports.persistence.AgentMessageDto;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.Role;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.session.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class JdbcMessageRepositoryTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JdbcMessageRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void appendAndFindByRunId() {
        JdbcRunRepository runRepo = new JdbcRunRepository(jdbcTemplate);
        Session session = Session.create("sess-msg", "/tmp", Instant.now());
        runRepo.saveSession(session);
        AgentRun run = AgentRun.start("run-msg", "sess-msg", 5, Instant.now());
        runRepo.saveRunStarted(run, "agent", "test");

        Message userMsg = Message.user("hello");
        Message assistantMsg = Message.assistant("hi there");
        Message toolMsg = Message.toolObservation("tc-1", "file content");

        repository.append("run-msg", "sess-msg", userMsg);
        repository.append("run-msg", "sess-msg", assistantMsg);
        repository.append("run-msg", "sess-msg", toolMsg);

        List<AgentMessageDto> found = repository.findByRunId("run-msg");
        assertThat(found).hasSize(3);
        assertThat(found.get(0).role()).isEqualTo(Role.USER);
        assertThat(found.get(0).content()).isEqualTo("hello");
        assertThat(found.get(1).role()).isEqualTo(Role.ASSISTANT);
        assertThat(found.get(2).role()).isEqualTo(Role.USER);
        assertThat(found.get(2).toolCallId()).isEqualTo("tc-1");
    }

    @Test
    void findByRunIdReturnsEmptyForMissingRun() {
        List<AgentMessageDto> found = repository.findByRunId("missing-run");
        assertThat(found).isEmpty();
    }

    @Test
    void assistantMessageWithToolCallsRoundTrips() {
        JdbcRunRepository runRepo = new JdbcRunRepository(jdbcTemplate);
        Session session = Session.create("sess-tool", "/tmp", Instant.now());
        runRepo.saveSession(session);
        AgentRun run = AgentRun.start("run-tool", "sess-tool", 5, Instant.now());
        runRepo.saveRunStarted(run, "agent", "test");

        List<ToolCall> toolCalls = List.of(ToolCall.of("t1", "read_file", "{\"path\":\"x.txt\"}"));
        Message assistant = Message.assistantWithToolCalls("", toolCalls);
        repository.append("run-tool", "sess-tool", assistant);

        List<AgentMessageDto> found = repository.findByRunId("run-tool");
        assertThat(found).hasSize(1);
        AgentMessageDto dto = found.get(0);
        assertThat(dto.toolCallsJson()).isNotBlank();

        Message reconstructed = dto.toMessage(objectMapper);
        assertThat(reconstructed.role()).isEqualTo(Role.ASSISTANT);
        assertThat(reconstructed.toolCalls()).hasSize(1);
        assertThat(reconstructed.toolCalls().get(0).id()).isEqualTo("t1");
        assertThat(reconstructed.toolCalls().get(0).name()).isEqualTo("read_file");
        assertThat(reconstructed.toolCalls().get(0).argumentsJson()).isEqualTo("{\"path\":\"x.txt\"}");
    }

    @Test
    void findBySessionIdOrdersAndLimitsMessages() {
        JdbcRunRepository runRepo = new JdbcRunRepository(jdbcTemplate);
        Session session = Session.create("sess-limit", "/tmp", Instant.now());
        runRepo.saveSession(session);
        AgentRun run = AgentRun.start("run-limit", "sess-limit", 5, Instant.now());
        runRepo.saveRunStarted(run, "agent", "test");

        for (int i = 0; i < 5; i++) {
            repository.append("run-limit", "sess-limit", Message.user("msg-" + i));
        }

        List<AgentMessageDto> found = repository.findBySessionId("sess-limit", 3);
        assertThat(found).hasSize(3);
        // Should return the last 3 messages in ascending order.
        assertThat(found.get(0).content()).isEqualTo("msg-2");
        assertThat(found.get(1).content()).isEqualTo("msg-3");
        assertThat(found.get(2).content()).isEqualTo("msg-4");
    }

    @Test
    void findBySessionIdExcludesSystemMessages() {
        JdbcRunRepository runRepo = new JdbcRunRepository(jdbcTemplate);
        Session session = Session.create("sess-no-sys", "/tmp", Instant.now());
        runRepo.saveSession(session);
        AgentRun run = AgentRun.start("run-no-sys", "sess-no-sys", 5, Instant.now());
        runRepo.saveRunStarted(run, "agent", "test");

        repository.append("run-no-sys", "sess-no-sys", Message.system("sys"));
        repository.append("run-no-sys", "sess-no-sys", Message.user("hello"));
        repository.append("run-no-sys", "sess-no-sys", Message.assistant("hi"));

        List<AgentMessageDto> found = repository.findBySessionId("sess-no-sys", 0);
        assertThat(found).hasSize(2);
        assertThat(found).noneMatch(dto -> dto.role() == Role.SYSTEM);
    }

    @Test
    void findBySessionIdReturnsEmptyForUnknownSession() {
        List<AgentMessageDto> found = repository.findBySessionId("unknown-session", 10);
        assertThat(found).isEmpty();
    }

    @Test
    void findBySessionIdWithZeroLimitReturnsAll() {
        JdbcRunRepository runRepo = new JdbcRunRepository(jdbcTemplate);
        Session session = Session.create("sess-all", "/tmp", Instant.now());
        runRepo.saveSession(session);
        AgentRun run = AgentRun.start("run-all", "sess-all", 5, Instant.now());
        runRepo.saveRunStarted(run, "agent", "test");

        repository.append("run-all", "sess-all", Message.user("a"));
        repository.append("run-all", "sess-all", Message.user("b"));

        List<AgentMessageDto> found = repository.findBySessionId("sess-all", 0);
        assertThat(found).hasSize(2);
    }

    @Test
    void toolObservationToolCallIdRoundTrips() {
        JdbcRunRepository runRepo = new JdbcRunRepository(jdbcTemplate);
        Session session = Session.create("sess-tcid", "/tmp", Instant.now());
        runRepo.saveSession(session);
        AgentRun run = AgentRun.start("run-tcid", "sess-tcid", 5, Instant.now());
        runRepo.saveRunStarted(run, "agent", "test");

        repository.append("run-tcid", "sess-tcid", Message.toolObservation("call-42", "output"));

        List<AgentMessageDto> found = repository.findByRunId("run-tcid");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).toolCallId()).isEqualTo("call-42");

        Message reconstructed = found.get(0).toMessage(objectMapper);
        assertThat(reconstructed.toolCallId()).isEqualTo("call-42");
    }

    @Test
    void sessionIsolation() {
        JdbcRunRepository runRepo = new JdbcRunRepository(jdbcTemplate);
        Session s1 = Session.create("sess-a", "/tmp", Instant.now());
        Session s2 = Session.create("sess-b", "/tmp", Instant.now());
        runRepo.saveSession(s1);
        runRepo.saveSession(s2);
        AgentRun r1 = AgentRun.start("run-a", "sess-a", 5, Instant.now());
        AgentRun r2 = AgentRun.start("run-b", "sess-b", 5, Instant.now());
        runRepo.saveRunStarted(r1, "agent", "test");
        runRepo.saveRunStarted(r2, "agent", "test");

        repository.append("run-a", "sess-a", Message.user("A"));
        repository.append("run-b", "sess-b", Message.user("B"));

        assertThat(repository.findBySessionId("sess-a", 0)).hasSize(1);
        assertThat(repository.findBySessionId("sess-b", 0)).hasSize(1);
        assertThat(repository.findBySessionId("sess-a", 0).get(0).content()).isEqualTo("A");
        assertThat(repository.findBySessionId("sess-b", 0).get(0).content()).isEqualTo("B");
    }
}

package com.tinyclaw.adapters.persistence;

import com.tinyclaw.application.persistence.AgentMessageDto;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.Role;
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
}

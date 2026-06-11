package com.tinyclaw.adapters.persistence;

import com.tinyclaw.ports.persistence.ToolExecutionRecord;
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
class JdbcToolExecutionRepositoryTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JdbcToolExecutionRepository repository;

    @Test
    void appendAndFindByRunId() {
        JdbcRunRepository runRepo = new JdbcRunRepository(jdbcTemplate);
        Session session = Session.create("sess-te", "/tmp", Instant.now());
        runRepo.saveSession(session);
        AgentRun run = AgentRun.start("run-te", "sess-te", 5, Instant.now());
        runRepo.saveRunStarted(run, "plan", "test");

        ToolExecutionRecord record1 = new ToolExecutionRecord(
            "te-1", "run-te", "sess-te", "step-1", "write_file",
            "{\"path\":\"a.txt\"}", "ok", false,
            Instant.now(), Instant.now()
        );
        ToolExecutionRecord record2 = new ToolExecutionRecord(
            "te-2", "run-te", "sess-te", "step-2", "read_file",
            "{\"path\":\"a.txt\"}", "content", false,
            Instant.now(), Instant.now()
        );

        repository.append("run-te", record1);
        repository.append("run-te", record2);

        List<ToolExecutionRecord> found = repository.findByRunId("run-te");
        assertThat(found).hasSize(2);
        assertThat(found.get(0).stepId()).isEqualTo("step-1");
        assertThat(found.get(0).toolName()).isEqualTo("write_file");
        assertThat(found.get(1).stepId()).isEqualTo("step-2");
        assertThat(found.get(1).output()).isEqualTo("content");
    }

    @Test
    void findByRunIdReturnsEmptyForMissingRun() {
        List<ToolExecutionRecord> found = repository.findByRunId("missing-run");
        assertThat(found).isEmpty();
    }
}

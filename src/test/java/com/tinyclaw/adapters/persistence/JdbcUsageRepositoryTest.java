package com.tinyclaw.adapters.persistence;

import com.tinyclaw.application.persistence.UsageRecord;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.session.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 integration test proving usage_records foreign key validity.
 */
@SpringBootTest
@ActiveProfiles("test")
class JdbcUsageRepositoryTest {

    @Autowired
    private JdbcRunRepository runRepository;

    @Autowired
    private JdbcUsageRepository usageRepository;

    @Test
    void usageRecordRequiresValidRunAndSession() {
        Session session = Session.create("usage-sess", "/tmp", Instant.now());
        AgentRun run = AgentRun.start("usage-run", "usage-sess", 3, Instant.now());
        runRepository.saveSession(session);
        runRepository.saveRunStarted(run, "fake", "test");

        UsageRecord record = new UsageRecord(
            "usage-run", "usage-sess", "fake", 100, 50, null, true, Instant.now()
        );
        usageRepository.save(record);

        List<UsageRecord> found = usageRepository.findByRunId("usage-run");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).promptTokens()).isEqualTo(100);
        assertThat(found.get(0).completionTokens()).isEqualTo(50);
    }
}

package com.tinyclaw.adapters.reporter;

import com.tinyclaw.ports.persistence.UsageRecord;
import com.tinyclaw.config.TinyClawModelProperties;
import com.tinyclaw.domain.message.Usage;
import com.tinyclaw.ports.persistence.UsageRepositoryPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UsagePersistingReporterTest {

    static class InMemoryUsageRepository implements UsageRepositoryPort {
        final List<UsageRecord> records = new ArrayList<>();

        @Override
        public void save(UsageRecord record) {
            records.add(record);
        }

        @Override
        public List<UsageRecord> findByRunId(String runId) {
            return records.stream().filter(r -> r.runId().equals(runId)).toList();
        }
    }

    @Test
    void savesUsageRecordOnUsageEvent() {
        InMemoryUsageRepository repo = new InMemoryUsageRepository();
        TinyClawModelProperties props = new TinyClawModelProperties();
        UsagePersistingReporter reporter = new UsagePersistingReporter(repo, props);

        reporter.onUsage("run-1", "session-1", new Usage(100, 50), "gpt-4");

        assertThat(repo.records).hasSize(1);
        UsageRecord record = repo.records.get(0);
        assertThat(record.runId()).isEqualTo("run-1");
        assertThat(record.sessionId()).isEqualTo("session-1");
        assertThat(record.model()).isEqualTo("gpt-4");
        assertThat(record.promptTokens()).isEqualTo(100);
        assertThat(record.completionTokens()).isEqualTo(50);
        assertThat(record.success()).isTrue();
    }

    @Test
    void doesNotThrowWhenRepositoryFails() {
        UsageRepositoryPort failingRepo = new UsageRepositoryPort() {
            @Override public void save(UsageRecord record) { throw new RuntimeException("db down"); }
            @Override public List<UsageRecord> findByRunId(String runId) { return List.of(); }
        };
        UsagePersistingReporter reporter = new UsagePersistingReporter(failingRepo, new TinyClawModelProperties());

        reporter.onUsage("run-1", "session-1", new Usage(10, 20), "model");
    }

    @Test
    void fallsBackToUnknownWhenModelIsBlank() {
        InMemoryUsageRepository repo = new InMemoryUsageRepository();
        UsagePersistingReporter reporter = new UsagePersistingReporter(repo, new TinyClawModelProperties());

        reporter.onUsage("run-1", "session-1", new Usage(10, 20), null);
        assertThat(repo.records.get(0).model()).isEqualTo("unknown");

        reporter.onUsage("run-1", "session-1", new Usage(10, 20), "");
        assertThat(repo.records.get(1).model()).isEqualTo("unknown");
    }
}

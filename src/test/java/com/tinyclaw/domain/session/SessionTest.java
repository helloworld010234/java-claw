package com.tinyclaw.domain.session;

import com.tinyclaw.domain.common.TinyClawDomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionTest {

    @Test
    void createDefaultsToActive() {
        Instant now = Instant.parse("2026-06-10T00:00:00Z");
        Session session = Session.create("sess-1", "/workspace", now);
        assertThat(session.id()).isEqualTo("sess-1");
        assertThat(session.workDir()).isEqualTo("/workspace");
        assertThat(session.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(session.createdAt()).isEqualTo(now);
        assertThat(session.updatedAt()).isEqualTo(now);
    }

    @Test
    void archiveReturnsArchivedSession() {
        Instant created = Instant.parse("2026-06-10T00:00:00Z");
        Session session = Session.create("sess-1", "/workspace", created);
        Instant archived = Instant.parse("2026-06-10T01:00:00Z");
        Session archivedSession = session.archive(archived);
        assertThat(archivedSession.status()).isEqualTo(SessionStatus.ARCHIVED);
        assertThat(archivedSession.updatedAt()).isEqualTo(archived);
        assertThat(archivedSession.createdAt()).isEqualTo(created);
    }

    @Test
    void archiveTimeBeforeCreatedAtThrows() {
        Instant created = Instant.parse("2026-06-10T01:00:00Z");
        Session session = Session.create("sess-1", "/workspace", created);
        Instant beforeCreated = Instant.parse("2026-06-10T00:00:00Z");
        assertThatThrownBy(() -> session.archive(beforeCreated))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("archive time must not be before createdAt");
    }

    @Test
    void nullIdThrows() {
        assertThatThrownBy(() -> Session.create(null, "/workspace", Instant.now()))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("id");
    }

    @Test
    void nullWorkDirThrows() {
        assertThatThrownBy(() -> Session.create("sess-1", null, Instant.now()))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("workDir");
    }

    @Test
    void nullNowThrows() {
        assertThatThrownBy(() -> Session.create("sess-1", "/workspace", null))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("now");
    }
}

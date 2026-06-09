package com.tinyclaw.domain.approval;

import com.tinyclaw.domain.common.TinyClawDomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalRequestTest {

    @Test
    void pendingDefaultsToPending() {
        Instant now = Instant.parse("2026-06-10T00:00:00Z");
        ApprovalRequest req = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "write to /tmp/test", now);
        assertThat(req.id()).isEqualTo("apr-1");
        assertThat(req.runId()).isEqualTo("run-1");
        assertThat(req.sessionId()).isEqualTo("sess-1");
        assertThat(req.toolCallId()).isEqualTo("call-1");
        assertThat(req.toolName()).isEqualTo("write_file");
        assertThat(req.argumentsPreview()).isEqualTo("write to /tmp/test");
        assertThat(req.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(req.decisionReason()).isNull();
        assertThat(req.requestedAt()).isEqualTo(now);
        assertThat(req.decidedAt()).isNull();
    }

    @Test
    void approveSetsApproved() {
        Instant requested = Instant.parse("2026-06-10T00:00:00Z");
        ApprovalRequest req = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "write to /tmp/test", requested);
        Instant decided = Instant.parse("2026-06-10T00:01:00Z");
        ApprovalRequest approved = req.approve("operator confirmed", decided);
        assertThat(approved.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approved.decisionReason()).isEqualTo("operator confirmed");
        assertThat(approved.decidedAt()).isEqualTo(decided);
    }

    @Test
    void rejectSetsRejected() {
        Instant requested = Instant.parse("2026-06-10T00:00:00Z");
        ApprovalRequest req = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "write to /tmp/test", requested);
        Instant decided = Instant.parse("2026-06-10T00:01:00Z");
        ApprovalRequest rejected = req.reject("unsafe path", decided);
        assertThat(rejected.status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(rejected.decisionReason()).isEqualTo("unsafe path");
        assertThat(rejected.decidedAt()).isEqualTo(decided);
    }

    @Test
    void expireSetsExpired() {
        Instant requested = Instant.parse("2026-06-10T00:00:00Z");
        ApprovalRequest req = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "write to /tmp/test", requested);
        Instant decided = Instant.parse("2026-06-10T00:10:00Z");
        ApprovalRequest expired = req.expire(decided);
        assertThat(expired.status()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(expired.decidedAt()).isEqualTo(decided);
    }

    @Test
    void approveOnApprovedThrows() {
        Instant requested = Instant.parse("2026-06-10T00:00:00Z");
        ApprovalRequest req = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "write to /tmp/test", requested)
                .approve("ok", requested.plusSeconds(1));
        assertThatThrownBy(() -> req.approve("again", requested.plusSeconds(2)))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("Cannot perform 'approve'");
    }

    @Test
    void rejectOnRejectedThrows() {
        Instant requested = Instant.parse("2026-06-10T00:00:00Z");
        ApprovalRequest req = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "write to /tmp/test", requested)
                .reject("no", requested.plusSeconds(1));
        assertThatThrownBy(() -> req.reject("again", requested.plusSeconds(2)))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("Cannot perform 'reject'");
    }

    @Test
    void expireOnApprovedThrows() {
        Instant requested = Instant.parse("2026-06-10T00:00:00Z");
        ApprovalRequest req = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "write to /tmp/test", requested)
                .approve("ok", requested.plusSeconds(1));
        assertThatThrownBy(() -> req.expire(requested.plusSeconds(2)))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("Cannot perform 'expire'");
    }

    @Test
    void decidedAtBeforeRequestedAtThrows() {
        Instant requested = Instant.parse("2026-06-10T01:00:00Z");
        ApprovalRequest req = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "write to /tmp/test", requested);
        Instant before = Instant.parse("2026-06-10T00:00:00Z");
        assertThatThrownBy(() -> req.approve("ok", before))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("decision time must not be before requestedAt");
    }

    @Test
    void approveWithBlankReasonThrows() {
        Instant requested = Instant.parse("2026-06-10T00:00:00Z");
        ApprovalRequest req = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "write to /tmp/test", requested);
        assertThatThrownBy(() -> req.approve("  ", requested.plusSeconds(1)))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void nullArgumentsPreviewDefaultsToEmptyString() {
        Instant now = Instant.now();
        ApprovalRequest req = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", null, now);
        assertThat(req.argumentsPreview()).isEmpty();
    }
}

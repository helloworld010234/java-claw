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

    @Test
    void approvedCanBeMarkedResuming() {
        Instant requested = Instant.parse("2026-06-10T00:00:00Z");
        ApprovalRequest approved = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "args", requested)
                .approve("ok", requested.plusSeconds(1));
        Instant resumingAt = requested.plusSeconds(2);

        ApprovalRequest resuming = approved.markResuming("claiming for resume", resumingAt);

        assertThat(resuming.status()).isEqualTo(ApprovalStatus.RESUMING);
        assertThat(resuming.decisionReason()).isEqualTo("claiming for resume");
        assertThat(resuming.decidedAt()).isEqualTo(resumingAt);
    }

    @Test
    void resumingCanBeMarkedResumed() {
        Instant requested = Instant.parse("2026-06-10T00:00:00Z");
        ApprovalRequest resuming = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "args", requested)
                .approve("ok", requested.plusSeconds(1))
                .markResuming("claiming for resume", requested.plusSeconds(2));
        Instant resumedAt = requested.plusSeconds(3);

        ApprovalRequest resumed = resuming.markResumed("resumed successfully", resumedAt);

        assertThat(resumed.status()).isEqualTo(ApprovalStatus.RESUMED);
        assertThat(resumed.decisionReason()).isEqualTo("resumed successfully");
        assertThat(resumed.decidedAt()).isEqualTo(resumedAt);
    }

    @Test
    void approvedCannotBeMarkedResumedDirectly() {
        Instant requested = Instant.parse("2026-06-10T00:00:00Z");
        ApprovalRequest approved = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "args", requested)
                .approve("ok", requested.plusSeconds(1));

        assertThatThrownBy(() -> approved.markResumed("skip resuming", requested.plusSeconds(2)))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("Cannot perform 'markResumed'");
    }

    @Test
    void pendingCannotBeMarkedResuming() {
        Instant requested = Instant.parse("2026-06-10T00:00:00Z");
        ApprovalRequest pending = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "args", requested);

        assertThatThrownBy(() -> pending.markResuming("no", requested.plusSeconds(1)))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("Cannot perform 'markResuming'");
    }

    @Test
    void rejectedCannotBeMarkedResuming() {
        Instant requested = Instant.parse("2026-06-10T00:00:00Z");
        ApprovalRequest rejected = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "args", requested)
                .reject("no", requested.plusSeconds(1));

        assertThatThrownBy(() -> rejected.markResuming("no", requested.plusSeconds(2)))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("Cannot perform 'markResuming'");
    }

    @Test
    void expiredCannotBeMarkedResuming() {
        Instant requested = Instant.parse("2026-06-10T00:00:00Z");
        ApprovalRequest expired = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "args", requested)
                .expire(requested.plusSeconds(1));

        assertThatThrownBy(() -> expired.markResuming("no", requested.plusSeconds(2)))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("Cannot perform 'markResuming'");
    }

    @Test
    void resumedCannotBeMarkedResuming() {
        Instant requested = Instant.parse("2026-06-10T00:00:00Z");
        ApprovalRequest resumed = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "args", requested)
                .approve("ok", requested.plusSeconds(1))
                .markResuming("claim", requested.plusSeconds(2))
                .markResumed("done", requested.plusSeconds(3));

        assertThatThrownBy(() -> resumed.markResuming("no", requested.plusSeconds(4)))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("Cannot perform 'markResuming'");
    }

    @Test
    void pendingCannotBeMarkedResumed() {
        Instant requested = Instant.parse("2026-06-10T00:00:00Z");
        ApprovalRequest pending = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "args", requested);

        assertThatThrownBy(() -> pending.markResumed("no", requested.plusSeconds(1)))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("Cannot perform 'markResumed'");
    }

    @Test
    void rejectedCannotBeMarkedResumed() {
        Instant requested = Instant.parse("2026-06-10T00:00:00Z");
        ApprovalRequest rejected = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "args", requested)
                .reject("no", requested.plusSeconds(1));

        assertThatThrownBy(() -> rejected.markResumed("no", requested.plusSeconds(2)))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("Cannot perform 'markResumed'");
    }

    @Test
    void expiredCannotBeMarkedResumed() {
        Instant requested = Instant.parse("2026-06-10T00:00:00Z");
        ApprovalRequest expired = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "args", requested)
                .expire(requested.plusSeconds(1));

        assertThatThrownBy(() -> expired.markResumed("no", requested.plusSeconds(2)))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("Cannot perform 'markResumed'");
    }

    @Test
    void resumedCannotBeMarkedResumedAgain() {
        Instant requested = Instant.parse("2026-06-10T00:00:00Z");
        ApprovalRequest resumed = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "args", requested)
                .approve("ok", requested.plusSeconds(1))
                .markResuming("claim", requested.plusSeconds(2))
                .markResumed("first", requested.plusSeconds(3));

        assertThatThrownBy(() -> resumed.markResumed("second", requested.plusSeconds(4)))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("Cannot perform 'markResumed'");
    }

    @Test
    void markResumingRejectsBlankReason() {
        Instant requested = Instant.parse("2026-06-10T00:00:00Z");
        ApprovalRequest approved = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "args", requested)
                .approve("ok", requested.plusSeconds(1));

        assertThatThrownBy(() -> approved.markResuming("   ", requested.plusSeconds(2)))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void markResumingRejectsDecidedAtBeforeRequestedAt() {
        Instant requested = Instant.parse("2026-06-10T01:00:00Z");
        ApprovalRequest approved = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "args", requested)
                .approve("ok", requested.plusSeconds(1));
        Instant before = Instant.parse("2026-06-10T00:00:00Z");

        assertThatThrownBy(() -> approved.markResuming("ok", before))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("decision time must not be before requestedAt");
    }

    @Test
    void markResumedRejectsBlankReason() {
        Instant requested = Instant.parse("2026-06-10T00:00:00Z");
        ApprovalRequest resuming = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "args", requested)
                .approve("ok", requested.plusSeconds(1))
                .markResuming("claim", requested.plusSeconds(2));

        assertThatThrownBy(() -> resuming.markResumed("   ", requested.plusSeconds(3)))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void markResumedRejectsDecidedAtBeforeRequestedAt() {
        Instant requested = Instant.parse("2026-06-10T01:00:00Z");
        ApprovalRequest resuming = ApprovalRequest.pending(
                "apr-1", "run-1", "sess-1", "call-1", "write_file", "args", requested)
                .approve("ok", requested.plusSeconds(1))
                .markResuming("claim", requested.plusSeconds(2));
        Instant before = Instant.parse("2026-06-10T00:00:00Z");

        assertThatThrownBy(() -> resuming.markResumed("ok", before))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("decision time must not be before requestedAt");
    }
}

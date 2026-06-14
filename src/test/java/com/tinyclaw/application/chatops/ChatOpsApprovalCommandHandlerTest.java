package com.tinyclaw.application.chatops;

import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.run.AgentRunStatus;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.chatops.ChatOpsEvent;
import com.tinyclaw.ports.chatops.ChatOpsOutboundMessage;
import com.tinyclaw.ports.chatops.FakeChatOpsMessageSender;
import com.tinyclaw.ports.persistence.AgentRunSummary;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
import com.tinyclaw.ports.persistence.RunRepositoryPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ChatOpsApprovalCommandHandlerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-10T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void approveCommandTransitionsPendingToApproved() {
        InMemoryApprovalRepository repository = new InMemoryApprovalRepository();
        ApprovalRequest pending = ApprovalRequest.pending(
            "apr-1", "run-1", "sess-1", "tc-1", "shell_command", "args", clock.instant()
        );
        repository.save(pending);

        FakeChatOpsMessageSender sender = new FakeChatOpsMessageSender();
        ChatOpsApprovalCommandHandler handler = new ChatOpsApprovalCommandHandler(repository, clock);

        ChatOpsEvent event = new ChatOpsEvent("evt-1", "msg-1", "chat-1", "user-1", "approve apr-1",
            Instant.now(), ChatOpsEvent.Type.TEXT_MESSAGE);
        boolean handled = handler.handle(event, sender);

        assertThat(handled).isTrue();
        ApprovalRequest updated = repository.findById("apr-1").orElseThrow();
        assertThat(updated.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(sender.getMessages()).anyMatch(m -> m.text().contains("approved"));
    }

    @Test
    void rejectCommandTransitionsPendingToRejected() {
        InMemoryApprovalRepository repository = new InMemoryApprovalRepository();
        CapturingRunRepository runRepository = new CapturingRunRepository();
        ApprovalRequest pending = ApprovalRequest.pending(
            "apr-2", "run-1", "sess-1", "tc-1", "write_file", "args", clock.instant()
        );
        repository.save(pending);
        runRepository.summary = new AgentRunSummary(
            "run-1", "sess-1", "chatops", AgentRunStatus.WAITING_APPROVAL, 3,
            "prompt", "Approval required: apr-2", clock.instant(), null
        );

        FakeChatOpsMessageSender sender = new FakeChatOpsMessageSender();
        ChatOpsApprovalCommandHandler handler = new ChatOpsApprovalCommandHandler(
            repository, null, runRepository, clock);

        ChatOpsEvent event = new ChatOpsEvent("evt-1", "msg-1", "chat-1", "user-1", "reject apr-2",
            Instant.now(), ChatOpsEvent.Type.TEXT_MESSAGE);
        boolean handled = handler.handle(event, sender);

        assertThat(handled).isTrue();
        ApprovalRequest updated = repository.findById("apr-2").orElseThrow();
        assertThat(updated.status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(sender.getMessages()).anyMatch(m -> m.text().contains("rejected"));
        assertThat(runRepository.failedRunId).isEqualTo("run-1");
        assertThat(runRepository.failedTurnCount).isEqualTo(3);
        assertThat(runRepository.failedReason).isEqualTo("Approval rejected: apr-2");
    }

    @Test
    void nonCommandTextIsIgnored() {
        InMemoryApprovalRepository repository = new InMemoryApprovalRepository();
        ChatOpsApprovalCommandHandler handler = new ChatOpsApprovalCommandHandler(repository, clock);
        FakeChatOpsMessageSender sender = new FakeChatOpsMessageSender();

        ChatOpsEvent event = new ChatOpsEvent("evt-1", "msg-1", "chat-1", "user-1", "hello agent",
            Instant.now(), ChatOpsEvent.Type.TEXT_MESSAGE);
        boolean handled = handler.handle(event, sender);

        assertThat(handled).isFalse();
        assertThat(sender.getMessages()).isEmpty();
    }

    @Test
    void missingApprovalIdReportsFailure() {
        InMemoryApprovalRepository repository = new InMemoryApprovalRepository();
        ChatOpsApprovalCommandHandler handler = new ChatOpsApprovalCommandHandler(repository, clock);
        FakeChatOpsMessageSender sender = new FakeChatOpsMessageSender();

        ChatOpsEvent event = new ChatOpsEvent("evt-1", "msg-1", "chat-1", "user-1", "approve   ",
            Instant.now(), ChatOpsEvent.Type.TEXT_MESSAGE);
        boolean handled = handler.handle(event, sender);

        assertThat(handled).isTrue();
        assertThat(sender.getMessages()).anyMatch(m -> m.message() != null
            && m.message().type() == ChatOpsOutboundMessage.Type.RUN_FAILED);
    }

    @Test
    void unknownApprovalIdReportsFailure() {
        InMemoryApprovalRepository repository = new InMemoryApprovalRepository();
        ChatOpsApprovalCommandHandler handler = new ChatOpsApprovalCommandHandler(repository, clock);
        FakeChatOpsMessageSender sender = new FakeChatOpsMessageSender();

        ChatOpsEvent event = new ChatOpsEvent("evt-1", "msg-1", "chat-1", "user-1", "approve missing",
            Instant.now(), ChatOpsEvent.Type.TEXT_MESSAGE);
        boolean handled = handler.handle(event, sender);

        assertThat(handled).isTrue();
        assertThat(sender.getMessages()).anyMatch(m -> m.text().contains("not found"));
    }

    @Test
    void nonPendingApprovalCannotBeActedUpon() {
        InMemoryApprovalRepository repository = new InMemoryApprovalRepository();
        ApprovalRequest pending = ApprovalRequest.pending(
            "apr-3", "run-1", "sess-1", "tc-1", "shell_command", "args", clock.instant()
        );
        repository.save(pending);
        repository.update(pending.approve("already approved", clock.instant()));

        FakeChatOpsMessageSender sender = new FakeChatOpsMessageSender();
        ChatOpsApprovalCommandHandler handler = new ChatOpsApprovalCommandHandler(repository, clock);

        ChatOpsEvent event = new ChatOpsEvent("evt-1", "msg-1", "chat-1", "user-1", "approve apr-3",
            Instant.now(), ChatOpsEvent.Type.TEXT_MESSAGE);
        boolean handled = handler.handle(event, sender);

        assertThat(handled).isTrue();
        assertThat(sender.getMessages()).anyMatch(m -> m.text().contains("not pending"));
    }

    private static class InMemoryApprovalRepository implements ApprovalRepositoryPort {
        private final List<ApprovalRequest> requests = new ArrayList<>();

        @Override
        public void save(ApprovalRequest request) {
            requests.add(request);
        }

        @Override
        public Optional<ApprovalRequest> findById(String id) {
            return requests.stream().filter(r -> r.id().equals(id)).findFirst();
        }

        @Override
        public Optional<ApprovalRequest> findByRunIdAndToolCallId(String runId, String toolCallId) {
            return requests.stream()
                .filter(r -> r.runId().equals(runId) && r.toolCallId().equals(toolCallId))
                .findFirst();
        }

        @Override
        public List<ApprovalRequest> findByRunId(String runId) {
            return requests.stream().filter(r -> r.runId().equals(runId)).toList();
        }

        @Override
        public List<ApprovalRequest> findByStatus(ApprovalStatus status) {
            return requests.stream().filter(r -> r.status() == status).toList();
        }

        @Override
        public List<ApprovalRequest> findAll() {
            return List.copyOf(requests);
        }

        @Override
        public void update(ApprovalRequest request) {
            requests.removeIf(r -> r.id().equals(request.id()));
            requests.add(request);
        }

        @Override
        public boolean claimForResume(String approvalId, Instant now) {
            return false;
        }
    }

    private static class CapturingRunRepository implements RunRepositoryPort {
        AgentRunSummary summary;
        String failedRunId;
        int failedTurnCount = -1;
        String failedReason;

        @Override
        public void saveSession(Session session) {
        }

        @Override
        public Optional<Session> findSessionById(String sessionId) {
            return Optional.empty();
        }

        @Override
        public void saveRunStarted(AgentRun run, String mode, String prompt) {
        }

        @Override
        public void saveRunCompleted(AgentRun run) {
        }

        @Override
        public void saveRunCompleted(String runId, int turnCount, Instant completedAt) {
        }

        @Override
        public void saveRunFailed(AgentRun run, String reason) {
        }

        @Override
        public void saveRunFailed(String runId, int turnCount, String reason, Instant completedAt) {
            failedRunId = runId;
            failedTurnCount = turnCount;
            failedReason = reason;
        }

        @Override
        public Optional<AgentRunSummary> findById(String runId) {
            return summary != null && summary.id().equals(runId)
                ? Optional.of(summary)
                : Optional.empty();
        }
    }
}

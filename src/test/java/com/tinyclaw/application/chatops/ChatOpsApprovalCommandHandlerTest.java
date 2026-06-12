package com.tinyclaw.application.chatops;

import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
import com.tinyclaw.ports.chatops.ChatOpsEvent;
import com.tinyclaw.ports.chatops.ChatOpsOutboundMessage;
import com.tinyclaw.ports.chatops.FakeChatOpsMessageSender;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
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
        ApprovalRequest pending = ApprovalRequest.pending(
            "apr-2", "run-1", "sess-1", "tc-1", "write_file", "args", clock.instant()
        );
        repository.save(pending);

        FakeChatOpsMessageSender sender = new FakeChatOpsMessageSender();
        ChatOpsApprovalCommandHandler handler = new ChatOpsApprovalCommandHandler(repository, clock);

        ChatOpsEvent event = new ChatOpsEvent("evt-1", "msg-1", "chat-1", "user-1", "reject apr-2",
            Instant.now(), ChatOpsEvent.Type.TEXT_MESSAGE);
        boolean handled = handler.handle(event, sender);

        assertThat(handled).isTrue();
        ApprovalRequest updated = repository.findById("apr-2").orElseThrow();
        assertThat(updated.status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(sender.getMessages()).anyMatch(m -> m.text().contains("rejected"));
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
}

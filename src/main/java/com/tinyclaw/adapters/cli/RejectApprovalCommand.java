package com.tinyclaw.adapters.cli;

import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * CLI 命令：拒绝一个待审批请求。
 */
@Component
@Scope("prototype")
@CommandLine.Command(
    name = "reject",
    description = "Reject a pending approval request",
    mixinStandardHelpOptions = true
)
public class RejectApprovalCommand implements Callable<Integer> {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_INSTANT;

    private final ApprovalRepositoryPort approvalRepository;
    private final Clock clock;

    public RejectApprovalCommand(ApprovalRepositoryPort approvalRepository, Clock clock) {
        this.approvalRepository = approvalRepository;
        this.clock = clock;
    }

    @CommandLine.Option(
        names = {"--approval-id"},
        required = true,
        description = "Approval request ID"
    )
    private String approvalId;

    @CommandLine.Option(
        names = {"--reason"},
        required = true,
        description = "Reason for rejection"
    )
    private String reason;

    @Override
    public Integer call() {
        if (approvalRepository == null) {
            System.err.println("Approval repository not available");
            return 2;
        }

        if (reason == null || reason.isBlank()) {
            System.err.println("Reason must not be blank");
            return 2;
        }

        Optional<ApprovalRequest> maybe = approvalRepository.findById(approvalId);
        if (maybe.isEmpty()) {
            System.err.println("Approval not found: " + approvalId);
            return 2;
        }

        ApprovalRequest req = maybe.get();
        if (req.status() != ApprovalStatus.PENDING) {
            System.err.println("Approval is not pending: " + req.status());
            return 2;
        }

        ApprovalRequest rejected = req.reject(reason.trim(), Instant.now(clock));
        approvalRepository.update(rejected);

        System.out.println("approvalId: " + rejected.id());
        System.out.println("status: " + rejected.status());
        System.out.println("decidedAt: " + FMT.format(rejected.decidedAt()));
        System.out.println("decisionReason: " + rejected.decisionReason());

        return 0;
    }
}

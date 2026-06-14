package com.tinyclaw.adapters.cli;

import com.tinyclaw.application.approval.ApprovalResumeResult;
import com.tinyclaw.application.approval.ApprovalResumeService;
import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * CLI 命令：批准一个待审批请求并恢复执行。
 */
@Component
@Scope("prototype")
@CommandLine.Command(
    name = "approve",
    description = "Approve a pending approval request and resume the paused run",
    mixinStandardHelpOptions = true
)
public class ApproveCommand implements Callable<Integer> {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_INSTANT;

    private final ApprovalRepositoryPort approvalRepository;
    private final ApprovalResumeService approvalResumeService;
    private final Clock clock;

    public ApproveCommand(ApprovalRepositoryPort approvalRepository, Clock clock) {
        this(approvalRepository, null, clock);
    }

    @Autowired
    public ApproveCommand(ApprovalRepositoryPort approvalRepository,
                          ApprovalResumeService approvalResumeService,
                          Clock clock) {
        this.approvalRepository = approvalRepository;
        this.approvalResumeService = approvalResumeService;
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
        description = "Reason for approval"
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

        ApprovalRequest approved = req.approve(reason.trim(), Instant.now(clock));
        approvalRepository.update(approved);

        System.out.println("approvalId: " + approved.id());
        System.out.println("status: " + approved.status());
        System.out.println("decidedAt: " + FMT.format(approved.decidedAt()));
        System.out.println("decisionReason: " + approved.decisionReason());

        if (approvalResumeService != null) {
            System.out.println("Resuming run...");
            ApprovalResumeResult result = approvalResumeService.resume(approvalId);
            System.out.println("runId: " + result.runId());
            System.out.println("runStatus: " + result.runStatus());
            if (result.toolError()) {
                System.out.println("toolError: true");
                System.out.println("error: " + result.output());
                return result.runStatus().isFailure() ? 1 : 2;
            }
            if (!result.finalMessage().isBlank()) {
                System.out.println("finalMessage: " + result.finalMessage());
            }
        }

        return 0;
    }
}

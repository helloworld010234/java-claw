package com.tinyclaw.adapters.cli;

import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * CLI 命令：显示单个审批请求详情。
 */
@Component
@Scope("prototype")
@CommandLine.Command(
    name = "show",
    description = "Show details of a specific approval request",
    mixinStandardHelpOptions = true
)
public class ShowApprovalCommand implements Callable<Integer> {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_INSTANT;

    private final ApprovalRepositoryPort approvalRepository;

    public ShowApprovalCommand(ApprovalRepositoryPort approvalRepository) {
        this.approvalRepository = approvalRepository;
    }

    @CommandLine.Option(
        names = {"--approval-id"},
        required = true,
        description = "Approval request ID"
    )
    private String approvalId;

    @Override
    public Integer call() {
        if (approvalRepository == null) {
            System.err.println("Approval repository not available");
            return 2;
        }

        Optional<ApprovalRequest> maybe = approvalRepository.findById(approvalId);
        if (maybe.isEmpty()) {
            System.err.println("Approval not found: " + approvalId);
            return 2;
        }

        ApprovalRequest req = maybe.get();
        System.out.println("approvalId: " + req.id());
        System.out.println("runId: " + req.runId());
        System.out.println("sessionId: " + req.sessionId());
        System.out.println("toolCallId: " + req.toolCallId());
        System.out.println("toolName: " + req.toolName());
        System.out.println("status: " + req.status());
        System.out.println("argumentsPreview: " + req.argumentsPreview());
        System.out.println("requestedAt: " + FMT.format(req.requestedAt()));
        System.out.println("decidedAt: " + (req.decidedAt() != null ? FMT.format(req.decidedAt()) : ""));
        System.out.println("decisionReason: " + (req.decisionReason() != null ? req.decisionReason() : ""));

        return 0;
    }
}

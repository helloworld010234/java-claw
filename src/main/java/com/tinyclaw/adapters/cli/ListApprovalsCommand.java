package com.tinyclaw.adapters.cli;

import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI 命令：列出审批请求。
 */
@Component
@Scope("prototype")
@CommandLine.Command(
    name = "list",
    description = "List approval requests",
    mixinStandardHelpOptions = true
)
public class ListApprovalsCommand implements Callable<Integer> {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_INSTANT;

    private final ApprovalRepositoryPort approvalRepository;

    public ListApprovalsCommand(ApprovalRepositoryPort approvalRepository) {
        this.approvalRepository = approvalRepository;
    }

    @CommandLine.Option(
        names = {"--status"},
        description = "Filter by status: PENDING, APPROVED, REJECTED, EXPIRED"
    )
    private String status;

    @CommandLine.Option(
        names = {"--run-id"},
        description = "Filter by run ID"
    )
    private String runId;

    @Override
    public Integer call() {
        if (approvalRepository == null) {
            System.err.println("Approval repository not available");
            return 2;
        }

        List<ApprovalRequest> requests;
        if (runId != null && !runId.isBlank()) {
            requests = approvalRepository.findByRunId(runId);
        } else if (status != null && !status.isBlank()) {
            try {
                ApprovalStatus filterStatus = ApprovalStatus.valueOf(status.toUpperCase());
                requests = approvalRepository.findByStatus(filterStatus);
            } catch (IllegalArgumentException e) {
                System.err.println("Invalid status: " + status);
                return 2;
            }
        } else {
            requests = approvalRepository.findAll();
        }

        if (requests.isEmpty()) {
            System.out.println("No approvals found.");
            return 0;
        }

        for (ApprovalRequest req : requests) {
            System.out.println("approvalId: " + req.id()
                + " runId: " + req.runId()
                + " toolName: " + req.toolName()
                + " status: " + req.status()
                + " requestedAt: " + FMT.format(req.requestedAt()));
        }
        return 0;
    }
}

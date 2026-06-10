package com.tinyclaw.adapters.cli;

import com.tinyclaw.application.approval.ApprovalResumeResult;
import com.tinyclaw.application.approval.ApprovalResumeService;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * CLI 命令：恢复执行一个已审批的工具调用。
 */
@Component
@Scope("prototype")
@CommandLine.Command(
    name = "resume",
    description = "Resume execution of an approved tool call",
    mixinStandardHelpOptions = true
)
public class ResumeApprovalCommand implements Callable<Integer> {

    private final ApprovalResumeService approvalResumeService;

    public ResumeApprovalCommand(ApprovalResumeService approvalResumeService) {
        this.approvalResumeService = approvalResumeService;
    }

    @CommandLine.Option(
        names = {"--approval-id"},
        required = true,
        description = "Approved approval request ID"
    )
    private String approvalId;

    @Override
    public Integer call() {
        if (approvalResumeService == null) {
            System.err.println("Approval resume service not available");
            return 2;
        }

        ApprovalResumeResult result = approvalResumeService.resume(approvalId);

        System.out.println("approvalId: " + result.approvalId());
        if (!result.runId().isBlank()) {
            System.out.println("runId: " + result.runId());
        }
        if (!result.toolCallId().isBlank()) {
            System.out.println("toolCallId: " + result.toolCallId());
        }
        if (!result.toolName().isBlank()) {
            System.out.println("toolName: " + result.toolName());
        }

        if (!result.resumed()) {
            System.out.println("status: resume_failed");
            System.out.println("toolError: true");
            System.out.println("error: " + result.output());
            return 2;
        }

        System.out.println("status: resumed");
        System.out.println("toolError: " + result.toolError());
        if (result.toolError()) {
            System.out.println("error: " + result.output());
            return 1;
        }

        System.out.println("runStatus: " + result.runStatus());
        if (result.output() != null && !result.output().isBlank()) {
            System.out.println("output: " + result.output().trim());
        }

        return 0;
    }
}

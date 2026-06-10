package com.tinyclaw.adapters.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine;

/**
 * Picocli approvals 命令组，聚合审批查询与决策子命令。
 */
@Component
@CommandLine.Command(
    name = "approvals",
    description = "Query and manage approval requests",
    mixinStandardHelpOptions = true,
    subcommands = {ListApprovalsCommand.class, ShowApprovalCommand.class, ApproveCommand.class, RejectApprovalCommand.class, ResumeApprovalCommand.class}
)
public class ApprovalsCommand implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}

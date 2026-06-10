package com.tinyclaw.adapters.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine;

/**
 * Picocli runs 命令组，聚合 run 审计查询子命令。
 */
@Component
@CommandLine.Command(
    name = "runs",
    description = "Query agent run audit records",
    mixinStandardHelpOptions = true,
    subcommands = {ShowRunCommand.class}
)
public class RunsCommand implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}

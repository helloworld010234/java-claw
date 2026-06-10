package com.tinyclaw.adapters.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Picocli 顶层命令，聚合所有子命令。
 */
@Component
@Command(
    name = "tiny-claw",
    description = "TinyClaw Agent Harness CLI",
    mixinStandardHelpOptions = true,
    subcommands = {RunCommand.class, ToolCommand.class, RunsCommand.class}
)
public class RootCommand implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}

package com.tinyclaw.adapters.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * CLI run 命令：执行单次 Agent 任务。
 *
 * <p>Milestone 1 最小行为：校验参数、解析 workspace、打印任务信息并退出。</p>
 */
@Command(
    name = "run",
    description = "Run a single agent task with the given prompt and workspace",
    mixinStandardHelpOptions = true
)
public class RunCommand implements Runnable {

    @Option(
        names = {"--prompt"},
        required = true,
        description = "User prompt for the agent task"
    )
    private String prompt;

    @Option(
        names = {"--dir"},
        description = "Workspace directory (default: current directory)"
    )
    private String dir;

    @Option(
        names = {"--session"},
        description = "Session ID (default: auto-generated UUID)"
    )
    private String sessionId;

    @Override
    public void run() {
        String effectiveSessionId = sessionId != null && !sessionId.isBlank()
            ? sessionId
            : UUID.randomUUID().toString();

        Path workspace = resolveWorkspace(dir);

        System.out.println("sessionId: " + effectiveSessionId);
        System.out.println("workspace: " + workspace.toAbsolutePath());
        System.out.println("prompt: " + prompt);
    }

    private Path resolveWorkspace(String dir) {
        if (dir == null || dir.isBlank()) {
            return Paths.get("").toAbsolutePath().normalize();
        }

        Path path = Paths.get(dir).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw new CommandLine.ParameterException(
                new CommandLine(this),
                "Directory does not exist: " + dir
            );
        }
        if (!Files.isDirectory(path)) {
            throw new CommandLine.ParameterException(
                new CommandLine(this),
                "Path is not a directory: " + dir
            );
        }
        return path;
    }
}

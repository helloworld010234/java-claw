package com.tinyclaw.adapters.cli;

import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * CLI tool 命令：执行已注册的 agent 工具。
 *
 * <p>打通 CLI 参数 → ToolCall → ToolRegistry → AgentTool → ToolResult → CLI 输出。</p>
 */
@Component
@Scope("prototype")
@Command(
    name = "tool",
    description = "Execute a registered agent tool",
    mixinStandardHelpOptions = true
)
public class ToolCommand implements Callable<Integer> {

    private final ToolRegistry toolRegistry;

    public ToolCommand(ToolRegistry toolRegistry) {
        this.toolRegistry = DomainGuards.requireNonNull(toolRegistry, "toolRegistry");
    }

    @Option(
        names = {"--name"},
        required = true,
        description = "Tool name to execute"
    )
    private String name;

    @Option(
        names = {"--args"},
        required = true,
        description = "Tool arguments as JSON string"
    )
    private String args;

    @Option(
        names = {"--dir"},
        description = "Workspace directory (default: current directory)"
    )
    private String dir;

    @Option(
        names = {"--call-id"},
        description = "Tool call ID (default: auto-generated UUID)"
    )
    private String callId;

    @Override
    public Integer call() {
        Path workspace;
        try {
            workspace = resolveWorkspace(dir);
        } catch (CommandLine.ParameterException e) {
            System.err.println(e.getMessage());
            return 2;
        }

        String effectiveCallId = callId != null && !callId.isBlank()
            ? callId
            : UUID.randomUUID().toString();

        ToolCall call;
        try {
            call = ToolCall.of(effectiveCallId, name, args);
        } catch (Exception e) {
            System.err.println("Invalid tool call: " + e.getMessage());
            return 2;
        }

        ToolExecutionContext context = new ToolExecutionContext(workspace);
        ToolResult result = toolRegistry.execute(call, context);

        printResult(result);

        return result.error() ? 1 : 0;
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

    private void printResult(ToolResult result) {
        System.out.println("toolCallId: " + result.toolCallId());
        System.out.println("toolName: " + name);
        System.out.println("error: " + result.error());
        System.out.println("output:");
        System.out.println(result.output());
    }
}

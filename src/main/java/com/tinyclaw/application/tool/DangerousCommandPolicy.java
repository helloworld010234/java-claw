package com.tinyclaw.application.tool;

import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.ports.tool.ToolExecutionDecision;
import com.tinyclaw.ports.tool.ToolExecutionPolicy;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Policy that blocks dangerous shell commands.
 *
 * <p>Always allows non-shell tools and read-only operations.
 * For {@code shell_command}, rejects commands matching known dangerous patterns.</p>
 */
public class DangerousCommandPolicy implements ToolExecutionPolicy {

    public static final String SHELL_COMMAND = "shell_command";

    private static final List<DangerPattern> DANGER_PATTERNS = List.of(
        new DangerPattern(Pattern.compile("rm\\s+-rf"), "Command contains recursive delete (rm -rf)"),
        new DangerPattern(Pattern.compile("del\\s+/s"), "Command contains recursive delete (del /s)"),
        new DangerPattern(Pattern.compile("format\\s+"), "Command contains disk format"),
        new DangerPattern(Pattern.compile("shutdown\\s+"), "Command contains system shutdown"),
        new DangerPattern(Pattern.compile("systemctl\\s+"), "Command contains system service control (systemctl)"),
        new DangerPattern(Pattern.compile("kill\\s+"), "Command contains process kill"),
        new DangerPattern(Pattern.compile("sudo\\s+"), "Command contains privilege escalation (sudo)"),
        new DangerPattern(Pattern.compile(">\\s*\\*\\.java"), "Command redirects output to overwrite source files")
    );

    @Override
    public ToolExecutionDecision decide(ToolCall call) {
        String toolName = call.name();

        // Non-shell tools are always allowed for this policy
        if (!SHELL_COMMAND.equals(toolName)) {
            return ToolExecutionDecision.allow();
        }

        String args = call.argumentsJson();
        if (args == null || args.isBlank()) {
            return ToolExecutionDecision.allow();
        }

        String lowerArgs = args.toLowerCase();

        for (DangerPattern dp : DANGER_PATTERNS) {
            if (dp.pattern.matcher(lowerArgs).find()) {
                return ToolExecutionDecision.deny(
                    "Dangerous command blocked: " + dp.reason + ". " +
                    "If you need this operation, request explicit approval."
                );
            }
        }

        return ToolExecutionDecision.allow();
    }

    private record DangerPattern(Pattern pattern, String reason) {}
}

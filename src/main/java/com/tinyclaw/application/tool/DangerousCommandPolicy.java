package com.tinyclaw.application.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import com.tinyclaw.ports.tool.ToolExecutionDecision;
import com.tinyclaw.ports.tool.ToolExecutionPolicy;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Policy that blocks dangerous shell commands.
 *
 * <p>Always allows non-shell tools and read-only operations.
 * For {@code shell_command}, parses the {@code command} field from JSON and
 * rejects commands matching known dangerous patterns.</p>
 */
public class DangerousCommandPolicy implements ToolExecutionPolicy {

    public static final String SHELL_COMMAND = "shell_command";

    private static final List<DangerPattern> DANGER_PATTERNS = List.of(
        new DangerPattern(Pattern.compile("\\brm\\b\\s+-r"), "Command contains recursive delete (rm -r)"),
        new DangerPattern(Pattern.compile("\\bdel\\b\\s+/s"), "Command contains recursive delete (del /s)"),
        new DangerPattern(Pattern.compile("\\bformat\\b\\s+"), "Command contains disk format"),
        new DangerPattern(Pattern.compile("\\bshutdown\\b\\s+"), "Command contains system shutdown"),
        new DangerPattern(Pattern.compile("\\bsystemctl\\b\\s+"), "Command contains system service control (systemctl)"),
        new DangerPattern(Pattern.compile("\\bkill\\b\\s+"), "Command contains process kill"),
        new DangerPattern(Pattern.compile("\\bsudo\\b\\s+"), "Command contains privilege escalation (sudo)"),
        new DangerPattern(Pattern.compile(">\\s*\\*\\.java"), "Command redirects output to overwrite source files")
    );

    private final ObjectMapper objectMapper;

    public DangerousCommandPolicy() {
        this(new ObjectMapper());
    }

    public DangerousCommandPolicy(ObjectMapper objectMapper) {
        this.objectMapper = DomainGuards.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ToolExecutionDecision decide(ToolCall call, ToolExecutionContext context) {
        String toolName = call.name();

        // Non-shell tools are always allowed for this policy
        if (!SHELL_COMMAND.equals(toolName)) {
            return ToolExecutionDecision.allow();
        }

        String args = call.argumentsJson();
        if (args == null || args.isBlank()) {
            return ToolExecutionDecision.allow();
        }

        String command = extractCommand(args);
        if (command == null) {
            // Invalid JSON or missing command field: let ShellCommandTool handle the error
            return ToolExecutionDecision.allow();
        }

        String normalized = normalizeCommand(command);

        for (DangerPattern dp : DANGER_PATTERNS) {
            if (dp.pattern.matcher(normalized).find()) {
                return ToolExecutionDecision.deny(
                    "Dangerous command blocked: " + dp.reason + ". " +
                    "If you need this operation, request explicit approval."
                );
            }
        }

        return ToolExecutionDecision.allow();
    }

    /**
     * Extracts the {@code command} field from the arguments JSON.
     * Returns {@code null} if JSON is invalid, missing the field, or not a string.
     */
    private String extractCommand(String argsJson) {
        try {
            JsonNode root = objectMapper.readTree(argsJson);
            JsonNode commandNode = root.get("command");
            if (commandNode == null || !commandNode.isTextual()) {
                return null;
            }
            return commandNode.asText();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Normalizes a command string for pattern matching:
     * lower-case, collapse consecutive whitespace to a single space, trim.
     */
    static String normalizeCommand(String command) {
        if (command == null) {
            return "";
        }
        return command.toLowerCase().trim().replaceAll("\\s+", " ");
    }

    private record DangerPattern(Pattern pattern, String reason) {}
}

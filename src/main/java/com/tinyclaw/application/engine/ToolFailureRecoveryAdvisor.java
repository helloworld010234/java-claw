package com.tinyclaw.application.engine;

/**
 * Adds recovery hints to tool error output when a known failure pattern is detected.
 *
 * <p>Matches stable English fragments or project-internal error text so that
 * the LLM receives actionable guidance without relying on full Chinese error
 * messages from the Go baseline.</p>
 */
public class ToolFailureRecoveryAdvisor {

    /**
     * Analyzes a tool failure and appends a recovery hint when recognized.
     *
     * @param toolName  the tool that failed
     * @param rawOutput the raw tool output
     * @return enhanced output with a recovery hint, or the original output if unknown
     */
    public String advise(String toolName, String rawOutput) {
        if (toolName == null || rawOutput == null) {
            return rawOutput != null ? rawOutput : "";
        }

        String lower = rawOutput.toLowerCase();
        String hint = null;

        switch (toolName) {
            case "read_file", "write_file" -> {
                if (lower.contains("file does not exist") || lower.contains("no such file or directory")) {
                    hint = "Path may be incorrect. Use shell_command to list files (e.g., `ls -la` or `dir`) to verify the exact path before retrying.";
                }
            }
            case "edit_file" -> {
                if (lower.contains("oldtext not found")
                    || lower.contains("old_text not found")
                    || lower.contains("not found in file")) {
                    hint = "old_text does not match the current file content. Use read_file to fetch the latest content, then retry with the exact text.";
                } else if (lower.contains("appears multiple times") || lower.contains("multiple times in file")) {
                    hint = "old_text matched multiple locations. Include more surrounding lines to make the replacement unique.";
                }
            }
            case "shell_command" -> {
                if (lower.contains("command not found") || lower.contains("is not recognized")) {
                    hint = "Command not found. Verify the command name and that required tools are installed.";
                } else if (lower.contains("timed out") || rawOutput.contains("[Command timed out after")) {
                    hint = "Command timed out. For long-running services, run them in the background (e.g., `nohup ... &`).";
                }
            }
        }

        if (hint == null) {
            return rawOutput;
        }
        return rawOutput + "\n\n[Recovery hint]: " + hint;
    }
}

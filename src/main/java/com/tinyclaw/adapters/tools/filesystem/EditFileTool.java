package com.tinyclaw.adapters.tools.filesystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.common.TinyClawDomainException;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolDefinition;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.ports.tool.AgentTool;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Edits a UTF-8 text file in the workspace by fuzzy string replacement.
 *
 * <p>The tool tries increasingly tolerant matching strategies while still
 * requiring a unique match, matching the behavior of the Go reference
 * implementation:</p>
 *
 * <ol>
 *   <li>Exact match</li>
 *   <li>CRLF/LF normalization</li>
 *   <li>Trim-space match</li>
 *   <li>Line-by-line trim match</li>
 * </ol>
 *
 * <p>If the old text matches zero or multiple locations, the edit fails with a
 * clear message so the agent can read the file again or provide more context.</p>
 */
@Component
public class EditFileTool implements AgentTool {

    public static final String NAME = "edit_file";
    private static final String DESCRIPTION = "Edit a text file in the workspace by replacing a unique text snippet.";
    private static final String INPUT_SCHEMA_JSON = """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Relative path to the file within the workspace"
            },
            "oldText": {
              "type": "string",
              "description": "Text to replace. Must be unique in the file; provide enough context."
            },
            "newText": {
              "type": "string",
              "description": "Replacement text"
            },
            "old_text": {
              "type": "string",
              "description": "Alias for oldText"
            },
            "new_text": {
              "type": "string",
              "description": "Alias for newText"
            }
          },
          "required": ["path", "oldText", "newText"]
        }
        """;

    private final WorkspacePathResolver pathResolver;
    private final ObjectMapper objectMapper;

    public EditFileTool() {
        this(new WorkspacePathResolver(), new ObjectMapper());
    }

    public EditFileTool(WorkspacePathResolver pathResolver, ObjectMapper objectMapper) {
        this.pathResolver = DomainGuards.requireNonNull(pathResolver, "pathResolver");
        this.objectMapper = DomainGuards.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(NAME, DESCRIPTION, INPUT_SCHEMA_JSON);
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext context) {
        EditArguments arguments = parseArguments(call);
        if (arguments.error != null) {
            return ToolResult.failure(call.id(), arguments.error);
        }

        Path target;
        try {
            target = pathResolver.resolveExisting(context.workspaceRoot(), arguments.path);
        } catch (IOException e) {
            return ToolResult.failure(call.id(), "File does not exist or cannot be accessed: " + arguments.path);
        } catch (TinyClawDomainException e) {
            return ToolResult.failure(call.id(), e.getMessage());
        }

        if (Files.isDirectory(target)) {
            return ToolResult.failure(call.id(), "Path is a directory: " + arguments.path);
        }

        String content;
        try {
            content = Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ToolResult.failure(call.id(), "Failed to read file: " + e.getMessage());
        }

        ReplacementResult replacement = fuzzyReplace(content, arguments.oldText, arguments.newText);
        if (!replacement.success()) {
            return ToolResult.failure(call.id(), replacement.message());
        }

        try {
            Files.writeString(target, replacement.newContent(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ToolResult.failure(call.id(), "Failed to write file: " + e.getMessage());
        }

        return ToolResult.success(call.id(), "Edited file: " + arguments.path);
    }

    private ReplacementResult fuzzyReplace(String content, String oldText, String newText) {
        // L1: exact match
        int count = countOccurrences(content, oldText);
        if (count == 1) {
            return ReplacementResult.success(content.replace(oldText, newText));
        }
        if (count > 1) {
            return ReplacementResult.failure("oldText appears multiple times in file. Provide more context to make the match unique.");
        }

        // L2: CRLF/LF normalization
        String normalizedContent = content.replace("\r\n", "\n");
        String normalizedOld = oldText.replace("\r\n", "\n");

        count = countOccurrences(normalizedContent, normalizedOld);
        if (count == 1) {
            return ReplacementResult.success(normalizedContent.replace(normalizedOld, newText));
        }
        if (count > 1) {
            return ReplacementResult.failure("oldText appears multiple times in file after newline normalization. Provide more context to make the match unique.");
        }

        // L3: trim-space match
        String trimmedOld = normalizedOld.trim();
        if (!trimmedOld.isEmpty()) {
            count = countOccurrences(normalizedContent, trimmedOld);
            if (count == 1) {
                return ReplacementResult.success(normalizedContent.replace(trimmedOld, newText));
            }
            if (count > 1) {
                return ReplacementResult.failure("oldText appears multiple times in file after trim-space normalization. Provide more context to make the match unique.");
            }
        }

        // L4: line-by-line trim match
        return lineByLineReplace(normalizedContent, normalizedOld, newText);
    }

    private ReplacementResult lineByLineReplace(String content, String oldText, String newText) {
        String[] contentLines = content.split("\n", -1);
        String[] oldLines = oldText.trim().split("\n", -1);

        if (oldLines.length == 0 || contentLines.length < oldLines.length) {
            return ReplacementResult.failure("oldText not found in file. Try reading the file again to confirm the exact text.");
        }

        for (int i = 0; i < oldLines.length; i++) {
            oldLines[i] = oldLines[i].trim();
        }

        int matchCount = 0;
        int matchStartIndex = -1;
        int matchEndIndex = -1;

        for (int i = 0; i <= contentLines.length - oldLines.length; i++) {
            boolean isMatch = true;
            for (int j = 0; j < oldLines.length; j++) {
                if (!contentLines[i + j].trim().equals(oldLines[j])) {
                    isMatch = false;
                    break;
                }
            }
            if (isMatch) {
                matchCount++;
                matchStartIndex = i;
                matchEndIndex = i + oldLines.length;
            }
        }

        if (matchCount == 0) {
            return ReplacementResult.failure("oldText not found in file. Try reading the file again to confirm the exact text.");
        }
        if (matchCount > 1) {
            return ReplacementResult.failure("oldText matches " + matchCount + " locations in file after line-by-line normalization. Provide more context to make the match unique.");
        }

        List<String> newContentLines = new ArrayList<>();
        newContentLines.addAll(Arrays.asList(contentLines).subList(0, matchStartIndex));
        newContentLines.add(newText);
        newContentLines.addAll(Arrays.asList(contentLines).subList(matchEndIndex, contentLines.length));

        return ReplacementResult.success(String.join("\n", newContentLines));
    }

    private int countOccurrences(String content, String pattern) {
        if (pattern.isEmpty()) {
            return 0;
        }
        int count = 0;
        int fromIndex = 0;
        while ((fromIndex = content.indexOf(pattern, fromIndex)) != -1) {
            count++;
            fromIndex += pattern.length();
        }
        return count;
    }

    private EditArguments parseArguments(ToolCall call) {
        try {
            JsonNode root = objectMapper.readTree(call.argumentsJson());

            JsonNode pathNode = root.get("path");
            if (pathNode == null || !pathNode.isTextual()) {
                return EditArguments.error("Missing or invalid 'path' argument");
            }

            String oldText = readTextField(root, "oldText", "old_text");
            if (oldText == null) {
                return EditArguments.error("Missing or invalid 'oldText' argument");
            }
            if (oldText.isEmpty()) {
                return EditArguments.error("oldText must not be empty");
            }

            String newText = readTextField(root, "newText", "new_text");
            if (newText == null) {
                return EditArguments.error("Missing or invalid 'newText' argument");
            }

            return new EditArguments(pathNode.asText(), oldText, newText, null);
        } catch (IOException e) {
            return EditArguments.error("Invalid arguments JSON: " + e.getMessage());
        }
    }

    private String readTextField(JsonNode root, String primary, String alias) {
        JsonNode primaryNode = root.get(primary);
        if (primaryNode != null && primaryNode.isTextual()) {
            return primaryNode.asText();
        }
        JsonNode aliasNode = root.get(alias);
        if (aliasNode != null && aliasNode.isTextual()) {
            return aliasNode.asText();
        }
        return null;
    }

    private record EditArguments(String path, String oldText, String newText, String error) {
        private static EditArguments error(String message) {
            return new EditArguments(null, null, null, message);
        }
    }

    private record ReplacementResult(String newContent, String message) {
        static ReplacementResult success(String newContent) {
            return new ReplacementResult(newContent, null);
        }

        static ReplacementResult failure(String message) {
            return new ReplacementResult(null, message);
        }

        boolean success() {
            return newContent != null;
        }
    }

}

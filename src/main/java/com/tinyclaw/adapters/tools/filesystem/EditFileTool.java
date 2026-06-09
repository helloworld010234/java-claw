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

/**
 * Edits a UTF-8 text file in the workspace by exact string replacement.
 */
@Component
public class EditFileTool implements AgentTool {

    public static final String NAME = "edit_file";
    private static final String DESCRIPTION = "Edit a text file in the workspace by exact string replacement.";
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
              "description": "Exact text to replace"
            },
            "newText": {
              "type": "string",
              "description": "Replacement text"
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

        int firstIndex = content.indexOf(arguments.oldText);
        if (firstIndex == -1) {
            return ToolResult.failure(call.id(), "oldText not found in file: " + arguments.path);
        }

        int secondIndex = content.indexOf(arguments.oldText, firstIndex + arguments.oldText.length());
        if (secondIndex != -1) {
            return ToolResult.failure(call.id(), "oldText appears multiple times in file: " + arguments.path);
        }

        String newContent = content.substring(0, firstIndex)
            + arguments.newText
            + content.substring(firstIndex + arguments.oldText.length());

        try {
            Files.writeString(target, newContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ToolResult.failure(call.id(), "Failed to write file: " + e.getMessage());
        }

        return ToolResult.success(call.id(), "Edited file: " + arguments.path);
    }

    private EditArguments parseArguments(ToolCall call) {
        try {
            JsonNode root = objectMapper.readTree(call.argumentsJson());

            JsonNode pathNode = root.get("path");
            if (pathNode == null || !pathNode.isTextual()) {
                return EditArguments.error("Missing or invalid 'path' argument");
            }

            JsonNode oldTextNode = root.get("oldText");
            if (oldTextNode == null || !oldTextNode.isTextual()) {
                return EditArguments.error("Missing or invalid 'oldText' argument");
            }

            JsonNode newTextNode = root.get("newText");
            if (newTextNode == null || !newTextNode.isTextual()) {
                return EditArguments.error("Missing or invalid 'newText' argument");
            }

            String oldText = oldTextNode.asText();
            if (oldText.isEmpty()) {
                return EditArguments.error("oldText must not be empty");
            }

            return new EditArguments(pathNode.asText(), oldText, newTextNode.asText(), null);
        } catch (IOException e) {
            return EditArguments.error("Invalid arguments JSON: " + e.getMessage());
        }
    }

    private record EditArguments(String path, String oldText, String newText, String error) {
        private static EditArguments error(String message) {
            return new EditArguments(null, null, null, message);
        }
    }
}

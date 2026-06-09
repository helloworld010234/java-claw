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
import java.nio.file.StandardOpenOption;

/**
 * Writes UTF-8 text files into the workspace.
 */
@Component
public class WriteFileTool implements AgentTool {

    public static final String NAME = "write_file";
    private static final String DESCRIPTION = "Write a text file into the workspace.";
    private static final String INPUT_SCHEMA_JSON = """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Relative path to the file within the workspace"
            },
            "content": {
              "type": "string",
              "description": "Text content to write"
            },
            "overwrite": {
              "type": "boolean",
              "description": "Whether to overwrite an existing file",
              "default": false
            }
          },
          "required": ["path", "content"]
        }
        """;

    private final WorkspacePathResolver pathResolver;
    private final ObjectMapper objectMapper;

    public WriteFileTool() {
        this(new WorkspacePathResolver(), new ObjectMapper());
    }

    public WriteFileTool(WorkspacePathResolver pathResolver, ObjectMapper objectMapper) {
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
        WriteArguments arguments = parseArguments(call);
        if (arguments.error != null) {
            return ToolResult.failure(call.id(), arguments.error);
        }

        Path target;
        try {
            target = pathResolver.resolveWritable(context.workspaceRoot(), arguments.path);
        } catch (IOException e) {
            return ToolResult.failure(call.id(), "File path cannot be resolved: " + arguments.path);
        } catch (TinyClawDomainException e) {
            return ToolResult.failure(call.id(), e.getMessage());
        }

        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return ToolResult.failure(call.id(), "Parent path is not a directory: " + arguments.path);
        }
        if (Files.isDirectory(target)) {
            return ToolResult.failure(call.id(), "Path is a directory: " + arguments.path);
        }
        if (Files.exists(target) && !arguments.overwrite) {
            return ToolResult.failure(call.id(), "File already exists and overwrite is false: " + arguments.path);
        }

        try {
            Files.writeString(
                target,
                arguments.content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
            return ToolResult.success(call.id(), "Wrote file: " + arguments.path);
        } catch (IOException e) {
            return ToolResult.failure(call.id(), "Failed to write file: " + e.getMessage());
        }
    }

    private WriteArguments parseArguments(ToolCall call) {
        try {
            JsonNode root = objectMapper.readTree(call.argumentsJson());
            JsonNode pathNode = root.get("path");
            if (pathNode == null || !pathNode.isTextual()) {
                return WriteArguments.error("Missing or invalid 'path' argument");
            }

            JsonNode contentNode = root.get("content");
            if (contentNode == null || !contentNode.isTextual()) {
                return WriteArguments.error("Missing or invalid 'content' argument");
            }

            JsonNode overwriteNode = root.get("overwrite");
            boolean overwrite = false;
            if (overwriteNode != null) {
                if (!overwriteNode.isBoolean()) {
                    return WriteArguments.error("Invalid 'overwrite' type, expected boolean");
                }
                overwrite = overwriteNode.asBoolean();
            }

            return new WriteArguments(pathNode.asText(), contentNode.asText(), overwrite, null);
        } catch (IOException e) {
            return WriteArguments.error("Invalid arguments JSON: " + e.getMessage());
        }
    }

    private record WriteArguments(String path, String content, boolean overwrite, String error) {

        private static WriteArguments error(String message) {
            return new WriteArguments(null, null, false, message);
        }
    }
}

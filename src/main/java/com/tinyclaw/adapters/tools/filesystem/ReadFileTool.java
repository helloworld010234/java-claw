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
 * Reads UTF-8 text files from the workspace.
 */
@Component
public class ReadFileTool implements AgentTool {

    public static final String NAME = "read_file";
    private static final String DESCRIPTION = "Read a text file from the workspace.";
    private static final String INPUT_SCHEMA_JSON = """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Relative path to the file within the workspace"
            }
          },
          "required": ["path"]
        }
        """;

    private final WorkspacePathResolver pathResolver;
    private final ObjectMapper objectMapper;

    public ReadFileTool() {
        this(new WorkspacePathResolver(), new ObjectMapper());
    }

    public ReadFileTool(WorkspacePathResolver pathResolver, ObjectMapper objectMapper) {
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
        String pathArg;
        try {
            JsonNode root = objectMapper.readTree(call.argumentsJson());
            JsonNode pathNode = root.get("path");
            if (pathNode == null || !pathNode.isTextual()) {
                return ToolResult.failure(call.id(), "Missing or invalid 'path' argument");
            }
            pathArg = pathNode.asText();
        } catch (IOException e) {
            return ToolResult.failure(call.id(), "Invalid arguments JSON: " + e.getMessage());
        }

        Path path;
        try {
            path = pathResolver.resolveExisting(context.workspaceRoot(), pathArg);
        } catch (IOException e) {
            return ToolResult.failure(call.id(), "File does not exist or cannot be accessed: " + pathArg);
        } catch (TinyClawDomainException e) {
            return ToolResult.failure(call.id(), e.getMessage());
        }

        if (Files.isDirectory(path)) {
            return ToolResult.failure(call.id(), "Path is a directory: " + pathArg);
        }

        try {
            return ToolResult.success(call.id(), Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return ToolResult.failure(call.id(), "Failed to read file: " + e.getMessage());
        }
    }
}

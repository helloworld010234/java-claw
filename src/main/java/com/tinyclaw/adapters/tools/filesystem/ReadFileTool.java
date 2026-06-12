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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads UTF-8 text files from the workspace.
 *
 * <p>Large files are truncated to a bounded number of characters to prevent the
 * agent context from exploding. Binary files are detected by sampling the first
 * bytes and are rejected with a safe message. Invalid UTF-8 is reported as a
 * clear failure instead of an unhandled exception.</p>
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

    static final int MAX_OUTPUT_CHARS = 8000;
    static final String TRUNCATED_SUFFIX = "\n...[Output truncated to " + MAX_OUTPUT_CHARS + " chars]";
    static final int BINARY_CHECK_BYTES = 8192;

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

        if (isBinaryFile(path)) {
            return ToolResult.failure(call.id(), "File appears to be binary and cannot be read as text: " + pathArg);
        }

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);

        try (Reader reader = new InputStreamReader(Files.newInputStream(path), decoder)) {
            char[] buffer = new char[MAX_OUTPUT_CHARS + 1];
            int read = reader.read(buffer);
            if (read <= 0) {
                return ToolResult.success(call.id(), "");
            }
            boolean truncated = read > MAX_OUTPUT_CHARS;
            String content = new String(buffer, 0, Math.min(read, MAX_OUTPUT_CHARS));
            if (truncated) {
                content += TRUNCATED_SUFFIX;
            }
            return ToolResult.success(call.id(), content);
        } catch (IOException e) {
            if (isCharacterCodingException(e)) {
                return ToolResult.failure(call.id(), "File contains invalid UTF-8 or binary content and cannot be read as text: " + pathArg);
            }
            return ToolResult.failure(call.id(), "Failed to read file: " + e.getMessage());
        }
    }

    private boolean isBinaryFile(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] sample = new byte[BINARY_CHECK_BYTES];
            int read = in.read(sample);
            if (read <= 0) {
                return false;
            }
            for (int i = 0; i < read; i++) {
                if (sample[i] == 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private boolean isCharacterCodingException(IOException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof java.nio.charset.CharacterCodingException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Truncates content to the configured maximum number of characters.
     *
     * @param content the raw content
     * @return the truncated content, or the original content if it fits
     */
    static String truncate(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() <= MAX_OUTPUT_CHARS) {
            return content;
        }
        return content.substring(0, MAX_OUTPUT_CHARS) + TRUNCATED_SUFFIX;
    }
}

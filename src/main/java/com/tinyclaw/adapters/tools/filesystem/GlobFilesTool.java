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
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Lists files inside the workspace matching an optional glob pattern.
 *
 * <p>The tool resolves the requested base directory against the workspace root,
 * then walks the directory tree and returns matching file paths relative to the
 * workspace. Symbolic links that escape the workspace are rejected, and the
 * output is bounded to avoid flooding the agent context.</p>
 */
@Component
public class GlobFilesTool implements AgentTool {

    public static final String NAME = "glob_files";
    private static final String DESCRIPTION = "List files in the workspace matching a glob pattern.";
    private static final String INPUT_SCHEMA_JSON = """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Relative base directory within the workspace (default: workspace root)"
            },
            "glob": {
              "type": "string",
              "description": "Glob pattern to match file names, e.g. '*.java' or '**/*.md' (default: '*')"
            }
          }
        }
        """;

    static final int MAX_RESULTS = 100;
    static final int MAX_OUTPUT_CHARS = 8000;
    static final String TRUNCATED_SUFFIX = "\n...[Output truncated to " + MAX_OUTPUT_CHARS + " chars]";
    static final String RESULT_LIMIT_SUFFIX = "\n...[Result list truncated to " + MAX_RESULTS + " entries]";

    private final WorkspacePathResolver pathResolver;
    private final ObjectMapper objectMapper;

    public GlobFilesTool() {
        this(new WorkspacePathResolver(), new ObjectMapper());
    }

    public GlobFilesTool(WorkspacePathResolver pathResolver, ObjectMapper objectMapper) {
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
        GlobArguments arguments = parseArguments(call);
        if (arguments.error != null) {
            return ToolResult.failure(call.id(), arguments.error);
        }

        Path baseDir;
        try {
            baseDir = resolveBaseDirectory(context.workspaceRoot(), arguments.path);
        } catch (IOException e) {
            return ToolResult.failure(call.id(), "Base directory does not exist or cannot be accessed: " + arguments.path);
        } catch (TinyClawDomainException e) {
            return ToolResult.failure(call.id(), e.getMessage());
        }

        if (!Files.isDirectory(baseDir)) {
            return ToolResult.failure(call.id(), "Path is not a directory: " + arguments.path);
        }

        GlobMatcher matcher = createMatcher(baseDir, arguments.glob);
        Path workspaceRoot = context.workspaceRoot().toAbsolutePath().normalize();

        List<String> matches = new ArrayList<>();
        boolean resultLimitReached = false;
        try (Stream<Path> walk = Files.walk(baseDir, Integer.MAX_VALUE, FileVisitOption.FOLLOW_LINKS)) {
            for (Path path : walk.toList()) {
                if (matches.size() >= MAX_RESULTS) {
                    resultLimitReached = true;
                    break;
                }
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                if (!matcher.matches(baseDir.relativize(path))) {
                    continue;
                }
                if (!isInsideWorkspace(path, workspaceRoot)) {
                    continue;
                }
                matches.add(workspaceRoot.relativize(path.toAbsolutePath().normalize()).toString().replace("\\", "/"));
            }
        } catch (IOException e) {
            return ToolResult.failure(call.id(), "Failed to list files: " + e.getMessage());
        }

        Collections.sort(matches);
        String output = formatOutput(matches, resultLimitReached);
        return ToolResult.success(call.id(), output);
    }

    private Path resolveBaseDirectory(Path workspaceRoot, String path) throws IOException {
        if (path == null || path.isBlank()) {
            Path root = workspaceRoot.toAbsolutePath().normalize();
            return root.toRealPath();
        }
        return pathResolver.resolveExisting(workspaceRoot, path);
    }

    private GlobMatcher createMatcher(Path baseDir, String glob) {
        if (glob.contains("/") || glob.contains("\\\\") || glob.contains("**")) {
            PathMatcher pathMatcher = baseDir.getFileSystem().getPathMatcher("glob:" + glob);
            return relativePath -> pathMatcher.matches(relativePath);
        }
        // For simple file-name patterns like "*.java", match against the file name at any depth.
        PathMatcher nameMatcher = baseDir.getFileSystem().getPathMatcher("glob:" + glob);
        return relativePath -> nameMatcher.matches(relativePath.getFileName());
    }

    @FunctionalInterface
    private interface GlobMatcher {
        boolean matches(Path relativePath);
    }

    private boolean isInsideWorkspace(Path path, Path workspaceRoot) {
        try {
            Path realPath = path.toRealPath();
            Path realRoot = workspaceRoot.toRealPath();
            return realPath.startsWith(realRoot);
        } catch (IOException e) {
            return false;
        }
    }

    private String formatOutput(List<String> matches, boolean resultLimitReached) {
        if (matches.isEmpty()) {
            return "No matching files found.";
        }
        String joined = String.join("\n", matches);
        StringBuilder output = new StringBuilder(joined);
        if (resultLimitReached) {
            output.append(RESULT_LIMIT_SUFFIX);
        }
        if (output.length() > MAX_OUTPUT_CHARS) {
            output.setLength(MAX_OUTPUT_CHARS);
            output.append(TRUNCATED_SUFFIX);
        }
        return output.toString();
    }

    private GlobArguments parseArguments(ToolCall call) {
        try {
            JsonNode root = objectMapper.readTree(call.argumentsJson());

            String path = null;
            JsonNode pathNode = root.get("path");
            if (pathNode != null) {
                if (!pathNode.isTextual()) {
                    return GlobArguments.error("Invalid 'path' argument, expected string");
                }
                path = pathNode.asText();
            }

            String glob = "**";
            JsonNode globNode = root.get("glob");
            if (globNode != null) {
                if (!globNode.isTextual()) {
                    return GlobArguments.error("Invalid 'glob' argument, expected string");
                }
                glob = globNode.asText();
                if (glob.isBlank()) {
                    glob = "**";
                }
            }

            return new GlobArguments(path, glob, null);
        } catch (IOException e) {
            return GlobArguments.error("Invalid arguments JSON: " + e.getMessage());
        }
    }

    private record GlobArguments(String path, String glob, String error) {
        private static GlobArguments error(String message) {
            return new GlobArguments(null, null, message);
        }
    }
}

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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lists files inside the workspace matching an optional glob pattern.
 *
 * <p>The tool resolves the requested base directory against the workspace root,
 * then walks the directory tree without following symbolic links and returns
 * matching file paths relative to the workspace. Symbolic links are skipped,
 * and traversal stops as soon as either the result count or output character
 * limit is reached.</p>
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

        MatchCollector collector = new MatchCollector(baseDir, workspaceRoot, matcher);
        try {
            Files.walkFileTree(baseDir, collector);
        } catch (IOException e) {
            return ToolResult.failure(call.id(), "Failed to list files: " + e.getMessage());
        }

        List<String> matches = collector.matches;
        Collections.sort(matches);
        String output = formatOutput(matches, collector.resultLimitReached, collector.outputLimitReached);
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
        if (glob.contains("/") || glob.contains("\\") || glob.contains("**")) {
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

    private String formatOutput(List<String> matches, boolean resultLimitReached, boolean outputLimitReached) {
        if (matches.isEmpty()) {
            return "No matching files found.";
        }
        String joined = String.join("\n", matches);
        StringBuilder output = new StringBuilder(joined);
        if (resultLimitReached) {
            output.append(RESULT_LIMIT_SUFFIX);
        }
        if (outputLimitReached) {
            output.append(TRUNCATED_SUFFIX);
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

    /**
     * Streaming file-tree visitor that collects matches while respecting result
     * and output character limits. Symbolic links are never followed.
     */
    private static final class MatchCollector extends SimpleFileVisitor<Path> {

        private final Path baseDir;
        private final Path workspaceRoot;
        private final GlobMatcher matcher;
        private final List<String> matches = new ArrayList<>();

        private boolean resultLimitReached;
        private boolean outputLimitReached;
        private int outputLength;

        MatchCollector(Path baseDir, Path workspaceRoot, GlobMatcher matcher) {
            this.baseDir = baseDir;
            this.workspaceRoot = workspaceRoot;
            this.matcher = matcher;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            // Skip symbolic links and non-regular files. Because the walk does not
            // follow links, a symlink to a directory is also seen as a file here.
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) {
                return FileVisitResult.CONTINUE;
            }

            Path relativeToBase = baseDir.relativize(file);
            if (!matcher.matches(relativeToBase)) {
                return FileVisitResult.CONTINUE;
            }

            Path relativeToWorkspace = workspaceRoot.relativize(file.toAbsolutePath().normalize());
            String entry = relativeToWorkspace.toString().replace("\\", "/");

            int extra = matches.isEmpty() ? entry.length() : 1 + entry.length();
            if (outputLength + extra > MAX_OUTPUT_CHARS) {
                outputLimitReached = true;
                return FileVisitResult.TERMINATE;
            }
            outputLength += extra;
            matches.add(entry);

            if (matches.size() >= MAX_RESULTS) {
                resultLimitReached = true;
                return FileVisitResult.TERMINATE;
            }
            return FileVisitResult.CONTINUE;
        }
    }
}

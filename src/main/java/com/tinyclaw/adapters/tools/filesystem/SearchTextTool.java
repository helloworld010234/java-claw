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
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Searches for a text query inside workspace files.
 *
 * <p>The tool walks files under the requested base directory, optionally filters
 * them by a glob pattern, and returns every line that contains the query text.
 * Results include the relative file path, line number, and a trimmed snippet.
 * Output is bounded by result count and character limits to protect context size.</p>
 */
@Component
public class SearchTextTool implements AgentTool {

    public static final String NAME = "search_text";
    private static final String DESCRIPTION = "Search for a text query inside workspace files.";
    private static final String INPUT_SCHEMA_JSON = """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "Text to search for"
            },
            "path": {
              "type": "string",
              "description": "Relative base directory within the workspace (default: workspace root)"
            },
            "glob": {
              "type": "string",
              "description": "Optional glob pattern to restrict searched files, e.g. '*.java'"
            }
          },
          "required": ["query"]
        }
        """;

    static final int MAX_RESULTS = 50;
    static final int MAX_OUTPUT_CHARS = 8000;
    static final int SNIPPET_MAX_CHARS = 200;
    static final String TRUNCATED_SUFFIX = "\n...[Output truncated to " + MAX_OUTPUT_CHARS + " chars]";
    static final String RESULT_LIMIT_SUFFIX = "\n...[Result list truncated to " + MAX_RESULTS + " matches]";

    private final WorkspacePathResolver pathResolver;
    private final ObjectMapper objectMapper;

    public SearchTextTool() {
        this(new WorkspacePathResolver(), new ObjectMapper());
    }

    public SearchTextTool(WorkspacePathResolver pathResolver, ObjectMapper objectMapper) {
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
        SearchArguments arguments = parseArguments(call);
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

        PathMatcher matcher = null;
        if (arguments.glob != null && !arguments.glob.isBlank()) {
            matcher = baseDir.getFileSystem().getPathMatcher("glob:" + arguments.glob);
        }
        Path workspaceRoot = context.workspaceRoot().toAbsolutePath().normalize();

        List<String> matches = new ArrayList<>();
        boolean resultLimitReached = false;
        try (Stream<Path> walk = Files.walk(baseDir, Integer.MAX_VALUE, FileVisitOption.FOLLOW_LINKS)) {
            for (Path path : walk.toList()) {
                if (resultLimitReached) {
                    break;
                }
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                if (!isInsideWorkspace(path, workspaceRoot)) {
                    continue;
                }
                if (matcher != null && !matcher.matches(baseDir.relativize(path))) {
                    continue;
                }
                if (isBinary(path)) {
                    continue;
                }
                resultLimitReached = searchInFile(path, baseDir, workspaceRoot, arguments.query, matches);
            }
        } catch (IOException e) {
            return ToolResult.failure(call.id(), "Failed to search files: " + e.getMessage());
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

    private boolean isInsideWorkspace(Path path, Path workspaceRoot) {
        try {
            Path realPath = path.toRealPath();
            Path realRoot = workspaceRoot.toRealPath();
            return realPath.startsWith(realRoot);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isBinary(Path path) {
        try {
            byte[] sample = Files.readAllBytes(path);
            if (sample.length == 0) {
                return false;
            }
            int checkLen = Math.min(sample.length, 8192);
            for (int i = 0; i < checkLen; i++) {
                if (sample[i] == 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private boolean searchInFile(Path path, Path baseDir, Path workspaceRoot, String query, List<String> matches) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        String relativePath = workspaceRoot.relativize(path.toAbsolutePath().normalize()).toString().replace("\\", "/");
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains(query)) {
                matches.add(formatMatch(relativePath, i + 1, line));
                if (matches.size() >= MAX_RESULTS) {
                    return true;
                }
            }
        }
        return false;
    }

    private String formatMatch(String relativePath, int lineNumber, String line) {
        String snippet = line.trim();
        if (snippet.length() > SNIPPET_MAX_CHARS) {
            snippet = snippet.substring(0, SNIPPET_MAX_CHARS) + "...";
        }
        return relativePath + ":" + lineNumber + ": " + snippet;
    }

    private String formatOutput(List<String> matches, boolean resultLimitReached) {
        if (matches.isEmpty()) {
            return "No matches found.";
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

    private SearchArguments parseArguments(ToolCall call) {
        try {
            JsonNode root = objectMapper.readTree(call.argumentsJson());

            JsonNode queryNode = root.get("query");
            if (queryNode == null || !queryNode.isTextual()) {
                return SearchArguments.error("Missing or invalid 'query' argument");
            }
            String query = queryNode.asText();
            if (query.isEmpty()) {
                return SearchArguments.error("'query' must not be empty");
            }

            String path = null;
            JsonNode pathNode = root.get("path");
            if (pathNode != null) {
                if (!pathNode.isTextual()) {
                    return SearchArguments.error("Invalid 'path' argument, expected string");
                }
                path = pathNode.asText();
            }

            String glob = null;
            JsonNode globNode = root.get("glob");
            if (globNode != null) {
                if (!globNode.isTextual()) {
                    return SearchArguments.error("Invalid 'glob' argument, expected string");
                }
                glob = globNode.asText();
            }

            return new SearchArguments(query, path, glob, null);
        } catch (IOException e) {
            return SearchArguments.error("Invalid arguments JSON: " + e.getMessage());
        }
    }

    private record SearchArguments(String query, String path, String glob, String error) {
        private static SearchArguments error(String message) {
            return new SearchArguments(null, null, null, message);
        }
    }
}

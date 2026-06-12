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
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
 * Searches for a text query inside workspace files.
 *
 * <p>The tool walks files under the requested base directory without following
 * symbolic links, optionally filters them by a glob pattern, and returns every
 * line that contains the query text. Results include the relative file path,
 * line number, and a trimmed snippet. Binary files are sampled and skipped.
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
    static final int BINARY_CHECK_BYTES = 8192;
    static final int MAX_LINE_READ_CHARS = 8192;
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

        GlobMatcher matcher = null;
        if (arguments.glob != null && !arguments.glob.isBlank()) {
            matcher = createMatcher(baseDir, arguments.glob);
        }
        Path workspaceRoot = context.workspaceRoot().toAbsolutePath().normalize();

        SearchCollector collector = new SearchCollector(baseDir, workspaceRoot, arguments.query, matcher);
        try {
            Files.walkFileTree(baseDir, collector);
        } catch (IOException e) {
            return ToolResult.failure(call.id(), "Failed to search files: " + e.getMessage());
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
            return "No matches found.";
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

    /**
     * Streaming file-tree visitor that searches each eligible file line-by-line
     * without following symbolic links and without reading entire files into memory.
     */
    private static final class SearchCollector extends SimpleFileVisitor<Path> {

        private final Path baseDir;
        private final Path workspaceRoot;
        private final String query;
        private final GlobMatcher matcher;
        private final List<String> matches = new ArrayList<>();

        private boolean resultLimitReached;
        private boolean outputLimitReached;
        private int outputLength;

        SearchCollector(Path baseDir, Path workspaceRoot, String query, GlobMatcher matcher) {
            this.baseDir = baseDir;
            this.workspaceRoot = workspaceRoot;
            this.query = query;
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
            if (matcher != null && !matcher.matches(relativeToBase)) {
                return FileVisitResult.CONTINUE;
            }

            if (isBinary(file)) {
                return FileVisitResult.CONTINUE;
            }

            try {
                if (searchInFile(file)) {
                    return FileVisitResult.TERMINATE;
                }
            } catch (IOException e) {
                // Skip files that cannot be read as text (e.g. invalid UTF-8).
                return FileVisitResult.CONTINUE;
            }
            return FileVisitResult.CONTINUE;
        }

        private boolean isBinary(Path path) {
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

        private boolean searchInFile(Path file) throws IOException {
            Path relativeToWorkspace = workspaceRoot.relativize(file.toAbsolutePath().normalize());
            String relativePath = relativeToWorkspace.toString().replace("\\", "/");

            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

            try (Reader reader = new InputStreamReader(Files.newInputStream(file), decoder);
                 PushbackReader pushbackReader = new PushbackReader(reader)) {
                String line;
                int lineNumber = 0;
                while ((line = readBoundedLine(pushbackReader)) != null) {
                    lineNumber++;
                    if (line.contains(query)) {
                        String match = formatMatch(relativePath, lineNumber, line);
                        int extra = matches.isEmpty() ? match.length() : 1 + match.length();
                        if (outputLength + extra > MAX_OUTPUT_CHARS) {
                            outputLimitReached = true;
                            return true;
                        }
                        outputLength += extra;
                        matches.add(match);
                        if (matches.size() >= MAX_RESULTS) {
                            resultLimitReached = true;
                            return true;
                        }
                    }
                }
            } catch (IOException e) {
                // Treat unreadable text (e.g. invalid UTF-8) as a skip, not a crash.
                return false;
            }
            return false;
        }

        private String readBoundedLine(PushbackReader reader) throws IOException {
            StringBuilder buffer = new StringBuilder();
            int c;
            while ((c = reader.read()) != -1) {
                if (c == '\n') {
                    return buffer.toString();
                }
                if (c == '\r') {
                    int next = reader.read();
                    if (next != '\n' && next != -1) {
                        reader.unread(next);
                    }
                    return buffer.toString();
                }
                if (buffer.length() < MAX_LINE_READ_CHARS) {
                    buffer.append((char) c);
                }
                // Characters beyond the per-line limit are discarded.
            }
            if (buffer.isEmpty() && c == -1) {
                return null;
            }
            return buffer.toString();
        }

        private String formatMatch(String relativePath, int lineNumber, String line) {
            String snippet = line.trim();
            if (snippet.length() > SNIPPET_MAX_CHARS) {
                snippet = snippet.substring(0, SNIPPET_MAX_CHARS) + "...";
            }
            return relativePath + ":" + lineNumber + ": " + snippet;
        }
    }
}

package com.tinyclaw.adapters.workspace;

import com.tinyclaw.config.WorkspaceProperties;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Validates workspace paths requested via the Web API.
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>Null or blank workDir resolves to "default".</li>
 *   <li>Absolute paths are rejected.</li>
 *   <li>Paths containing {@code ..} are rejected.</li>
 *   <li>The resolved path must sit inside the configured workspace root.</li>
 *   <li>The workspace id must be in the configured {@code allowedIds} list.</li>
 * </ul>
 */
@Service
public class WorkspaceSecurityService {

    private final Path root;
    private final List<String> allowedIds;

    public WorkspaceSecurityService(WorkspaceProperties properties) {
        String rootPath = properties.getRoot();
        this.root = Paths.get(rootPath).toAbsolutePath().normalize();
        this.allowedIds = properties.getAllowedIds() != null ? List.copyOf(properties.getAllowedIds()) : List.of();
    }

    /**
     * Resolves a client-supplied workspace identifier into a safe server-side path.
     *
     * @param workDir the raw value from the HTTP request (may be null or blank)
     * @return the resolved, normalized path
     * @throws IllegalArgumentException if the path violates security rules
     */
    public Path resolveWorkspace(String workDir) {
        String raw = workDir != null && !workDir.isBlank() ? workDir : "default";

        Path candidate = Paths.get(raw);
        if (candidate.isAbsolute()) {
            throw new IllegalArgumentException("Absolute workspace paths are not allowed: " + raw);
        }

        String normalizedRaw = candidate.normalize().toString().replace('\\', '/');
        if (normalizedRaw.contains("..")) {
            throw new IllegalArgumentException("Workspace path contains directory traversal: " + raw);
        }

        String id = normalizedRaw;
        if (id.contains("/")) {
            id = id.substring(0, id.indexOf('/'));
        }

        if (!allowedIds.contains(id)) {
            throw new IllegalArgumentException("Workspace id not in allowlist: " + id);
        }

        Path resolved = root.resolve(candidate).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Workspace path escapes allowed root: " + raw);
        }

        return resolved;
    }

    public Path root() {
        return root;
    }

    public List<String> allowedIds() {
        return allowedIds;
    }
}

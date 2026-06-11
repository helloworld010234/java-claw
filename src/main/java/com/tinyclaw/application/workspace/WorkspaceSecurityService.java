package com.tinyclaw.application.workspace;

import com.tinyclaw.config.WorkspaceProperties;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Validates workspace paths requested via the Web API.
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>Absolute paths are rejected.</li>
 *   <li>Paths containing {@code ..} are rejected.</li>
 *   <li>The resolved path must sit inside the configured workspace root.</li>
 * </ul>
 */
@Service
public class WorkspaceSecurityService {

    private final Path root;

    public WorkspaceSecurityService(WorkspaceProperties properties) {
        String rootPath = properties.getRoot();
        this.root = Paths.get(rootPath).toAbsolutePath().normalize();
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

        Path resolved = root.resolve(candidate).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Workspace path escapes allowed root: " + raw);
        }

        return resolved;
    }

    public Path root() {
        return root;
    }
}

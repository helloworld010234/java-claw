package com.tinyclaw.adapters.tools.filesystem;

import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.common.TinyClawDomainException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves user-provided workspace paths and prevents workspace escape.
 */
@Component
public class WorkspacePathResolver {

    public Path resolve(Path workspaceRoot, String userPath) {
        DomainGuards.requireNonNull(workspaceRoot, "workspaceRoot");
        DomainGuards.requireNonBlank(userPath, "userPath");

        Path root = workspaceRoot.toAbsolutePath().normalize();
        String trimmedPath = userPath.trim();
        if (isAbsolute(trimmedPath)) {
            throw new TinyClawDomainException("Absolute paths are not allowed: " + trimmedPath);
        }

        Path candidate = root.resolve(Paths.get(trimmedPath)).normalize();
        if (!candidate.startsWith(root)) {
            throw new TinyClawDomainException("Path escapes workspace: " + trimmedPath);
        }
        return candidate;
    }

    public Path resolveExisting(Path workspaceRoot, String userPath) throws IOException {
        Path candidate = resolve(workspaceRoot, userPath);
        Path realRoot = workspaceRoot.toAbsolutePath().normalize().toRealPath();
        Path realCandidate = candidate.toRealPath();
        if (!realCandidate.startsWith(realRoot)) {
            throw new TinyClawDomainException("Path escapes workspace through a link: " + userPath);
        }
        return realCandidate;
    }

    public Path resolveWritable(Path workspaceRoot, String userPath) throws IOException {
        Path candidate = resolve(workspaceRoot, userPath);
        Path realRoot = workspaceRoot.toAbsolutePath().normalize().toRealPath();

        Path parent = candidate.getParent();
        if (parent == null) {
            throw new TinyClawDomainException("Path has no parent: " + userPath);
        }
        if (!Files.exists(parent)) {
            throw new TinyClawDomainException("Parent directory does not exist: " + userPath);
        }

        Path realParent = parent.toRealPath();
        if (!realParent.startsWith(realRoot)) {
            throw new TinyClawDomainException("Path parent escapes workspace through a link: " + userPath);
        }

        if (Files.exists(candidate)) {
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realRoot)) {
                throw new TinyClawDomainException("Path target escapes workspace through a link: " + userPath);
            }
        }

        return candidate;
    }

    private boolean isAbsolute(String path) {
        return path.startsWith("/")
            || path.startsWith("\\")
            || (path.length() > 1 && path.charAt(1) == ':');
    }
}

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
 *
 * <p>All resolution methods reject absolute paths and {@code ..} sequences that
 * would leave the workspace. Writable resolution additionally verifies every
 * existing ancestor directory against the real (symlink-resolved) filesystem so
 * that a path such as {@code symlink-dir/missing/file.txt} is rejected when the
 * symlink points outside the workspace.</p>
 */
@Component
public class WorkspacePathResolver {

    /**
     * Resolves a relative user path against the workspace root.
     *
     * @param workspaceRoot the workspace root directory
     * @param userPath      a relative path supplied by the user
     * @return the normalized absolute path inside the workspace
     * @throws TinyClawDomainException if the path is blank, absolute, or escapes the workspace
     */
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

    /**
     * Resolves an existing path and returns its real (symlink-resolved) location.
     *
     * @param workspaceRoot the workspace root directory
     * @param userPath      a relative path supplied by the user
     * @return the real path inside the workspace
     * @throws IOException if the path cannot be resolved on the filesystem
     */
    public Path resolveExisting(Path workspaceRoot, String userPath) throws IOException {
        Path candidate = resolve(workspaceRoot, userPath);
        Path realRoot = workspaceRoot.toAbsolutePath().normalize().toRealPath();
        Path realCandidate = candidate.toRealPath();
        if (!realCandidate.startsWith(realRoot)) {
            throw new TinyClawDomainException("Path escapes workspace through a link: " + userPath);
        }
        return realCandidate;
    }

    /**
     * Resolves a path that will be written, allowing missing parent directories
     * to be created later while still blocking symlink escapes.
     *
     * <p>The method walks from the workspace root to the parent of the target.
     * Every ancestor that already exists is resolved with {@code toRealPath()}
     * and must remain inside the real workspace root. Only after the nearest
     * existing ancestor is verified safe may the remaining missing directories
     * be created by the caller.</p>
     *
     * @param workspaceRoot the workspace root directory
     * @param userPath      a relative path supplied by the user
     * @return the normalized absolute path where the file should be written
     * @throws IOException if the filesystem cannot be inspected
     */
    public Path resolveWritable(Path workspaceRoot, String userPath) throws IOException {
        Path candidate = resolve(workspaceRoot, userPath);
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path realRoot = root.toRealPath();

        Path parent = candidate.getParent();
        if (parent == null) {
            throw new TinyClawDomainException("Path has no parent: " + userPath);
        }

        // Verify every existing ancestor from the workspace root to the target parent.
        // This blocks paths such as symlink-dir/missing/file.txt where symlink-dir
        // points outside the workspace.
        int rootNameCount = root.getNameCount();
        Path current = root;
        for (int i = rootNameCount; i < parent.getNameCount(); i++) {
            current = current.resolve(parent.getName(i));
            if (Files.exists(current)) {
                Path realCurrent = current.toRealPath();
                if (!realCurrent.startsWith(realRoot)) {
                    throw new TinyClawDomainException("Path escapes workspace through a link: " + userPath);
                }
            }
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

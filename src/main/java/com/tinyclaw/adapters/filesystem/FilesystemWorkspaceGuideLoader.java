package com.tinyclaw.adapters.filesystem;

import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.ports.workspace.WorkspaceGuideLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 基于文件系统的 {@link WorkspaceGuideLoader} 实现。
 *
 * <p>在工作区根目录下查找 {@code AGENTS.md} 文件。文件不存在、不可读、
 * 或不是常规文件时，返回 empty。</p>
 */
public class FilesystemWorkspaceGuideLoader implements WorkspaceGuideLoader {

    public static final String GUIDE_FILENAME = "AGENTS.md";

    @Override
    public Optional<String> loadGuide(String workspaceRoot) {
        DomainGuards.requireNonBlank(workspaceRoot, "workspaceRoot");
        Path path = Path.of(workspaceRoot).resolve(GUIDE_FILENAME);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return Optional.of(content);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}

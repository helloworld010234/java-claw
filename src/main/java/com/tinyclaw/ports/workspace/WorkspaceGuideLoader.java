package com.tinyclaw.ports.workspace;

import java.util.Optional;

/**
 * 加载工作区中的项目专属指南文件（如 AGENTS.md）。
 *
 * <p>实现负责具体的文件定位与读取策略（如文件系统、类路径、远程存储）。
 * 加载失败时返回 {@link Optional#empty()}，不抛出异常。</p>
 */
public interface WorkspaceGuideLoader {

    /**
     * 加载指定工作区根目录下的项目指南内容。
     *
     * @param workspaceRoot 工作区根目录的绝对路径，必须非空非 blank
     * @return 指南文件内容；若文件不存在或读取失败，返回 empty
     */
    Optional<String> loadGuide(String workspaceRoot);
}

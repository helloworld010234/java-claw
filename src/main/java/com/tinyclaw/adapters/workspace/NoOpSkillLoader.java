package com.tinyclaw.adapters.workspace;

import com.tinyclaw.ports.workspace.SkillLoader;

import java.util.Optional;

/**
 * 无操作（NoOp）的 {@link SkillLoader} 实现。
 *
 * <p>始终返回 empty，用于 Skills 功能尚未启用时的占位。</p>
 */
public class NoOpSkillLoader implements SkillLoader {

    @Override
    public Optional<String> loadSkills(String workspaceRoot) {
        return Optional.empty();
    }
}

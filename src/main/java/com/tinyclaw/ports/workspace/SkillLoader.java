package com.tinyclaw.ports.workspace;

import java.util.Optional;

/**
 * 加载工作区中的专业技能（Skills）定义。
 *
 * <p>实现负责扫描工作区下的技能目录（如 .claw/skills/），
 * 解析 SKILL.md 文件并返回格式化文本。加载失败时返回 {@link Optional#empty()}。</p>
 */
public interface SkillLoader {

    /**
     * 加载指定工作区下的所有可用技能内容。
     *
     * @param workspaceRoot 工作区根目录的绝对路径，必须非空非 blank
     * @return 格式化后的技能文本；若无技能或加载失败，返回 empty
     */
    Optional<String> loadSkills(String workspaceRoot);
}

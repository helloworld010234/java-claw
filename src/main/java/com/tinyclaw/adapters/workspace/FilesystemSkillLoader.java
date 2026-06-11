package com.tinyclaw.adapters.workspace;

import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.ports.workspace.SkillLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 基于文件系统的 {@link SkillLoader} 实现。
 *
 * <p>扫描工作区根目录下的 {@code .claw/skills/} 目录，递归查找所有 {@code SKILL.md} 文件。
 * 解析每个 SKILL.md 的 YAML frontmatter（name, description），并返回格式化后的技能文本。</p>
 *
 * <p>目录不存在、为空或加载失败时返回 {@link Optional#empty()}，不抛异常。</p>
 */
public class FilesystemSkillLoader implements SkillLoader {

    private static final String SKILLS_DIR = ".claw" + java.io.File.separator + "skills";
    private static final String SKILL_FILE = "SKILL.md";

    @Override
    public Optional<String> loadSkills(String workspaceRoot) {
        DomainGuards.requireNonBlank(workspaceRoot, "workspaceRoot");

        Path skillsDir = Path.of(workspaceRoot, SKILLS_DIR);
        if (!Files.exists(skillsDir) || !Files.isDirectory(skillsDir)) {
            return Optional.empty();
        }

        StringBuilder builder = new StringBuilder();
        builder.append("### 可用专业技能 (Agent Skills)\n");
        builder.append("以下是你拥有的标准化外挂技能，请在符合 description 描述的场景下严格遵循其正文指令：\n\n");

        try (Stream<Path> walk = Files.walk(skillsDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().equals(SKILL_FILE))
                .forEach(path -> appendSkill(builder, path));
        } catch (IOException e) {
            return Optional.empty();
        }

        if (builder.length() < 100) {
            return Optional.empty();
        }

        return Optional.of(builder.toString());
    }

    private void appendSkill(StringBuilder builder, Path path) {
        try {
            String content = Files.readString(path);
            Skill skill = parseSkillMD(content);

            builder.append("#### 技能名称: ").append(skill.name).append('\n');
            builder.append("**触发条件**: ").append(skill.description).append("\n\n");
            builder.append("**执行指南**:\n");
            builder.append(skill.body);
            builder.append("\n\n---\n");
        } catch (IOException e) {
            // 单个文件读取失败，跳过，不影响其他技能
        }
    }

    static Skill parseSkillMD(String content) {
        Skill skill = new Skill("Unknown Skill", "No description provided.", content);

        String trimmed = content.trim();
        if (trimmed.startsWith("---")) {
            int end = trimmed.indexOf("---", 3);
            if (end > 0) {
                String frontmatter = trimmed.substring(3, end).trim();
                String body = trimmed.substring(end + 3).trim();
                skill.body = body;

                for (String line : frontmatter.split("\r?\n")) {
                    line = line.trim();
                    if (line.startsWith("name:")) {
                        skill.name = line.substring("name:".length()).trim();
                    } else if (line.startsWith("description:")) {
                        skill.description = line.substring("description:".length()).trim();
                    }
                }
            }
        }

        return skill;
    }

    static class Skill {
        String name;
        String description;
        String body;

        Skill(String name, String description, String body) {
            this.name = name;
            this.description = description;
            this.body = body;
        }
    }
}

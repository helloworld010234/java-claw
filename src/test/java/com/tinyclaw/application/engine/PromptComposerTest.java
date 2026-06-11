package com.tinyclaw.application.engine;

import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.Role;
import com.tinyclaw.ports.workspace.SkillLoader;
import com.tinyclaw.ports.workspace.WorkspaceGuideLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptComposerTest {

    @Test
    void defaultComposerProducesSystemMessageWithCoreIdentityAndDiscipline() {
        PromptComposer composer = new PromptComposer();
        Message msg = composer.compose("/workspace");

        assertThat(msg.role()).isEqualTo(Role.SYSTEM);
        assertThat(msg.content()).contains("核心身份");
        assertThat(msg.content()).contains("TinyClaw");
        assertThat(msg.content()).contains("核心纪律");
        assertThat(msg.content()).contains("CRITICAL");
        assertThat(msg.content()).contains("始终用中文回复");
    }

    @Test
    void defaultComposerDoesNotIncludePlanModeContent() {
        PromptComposer composer = new PromptComposer();
        Message msg = composer.compose("/workspace");

        assertThat(msg.content()).doesNotContain("Plan Mode");
        assertThat(msg.content()).doesNotContain("STEP 1");
        assertThat(msg.content()).doesNotContain("TODO.md");
    }

    @Test
    void planModeComposerIncludesAllThreeSteps() {
        PromptComposer composer = new PromptComposer(true, null, null);
        Message msg = composer.compose("/workspace");

        assertThat(msg.content()).contains("Plan Mode: ON");
        assertThat(msg.content()).contains("STEP 1");
        assertThat(msg.content()).contains("STEP 2");
        assertThat(msg.content()).contains("STEP 3");
        assertThat(msg.content()).contains("PLAN.md");
        assertThat(msg.content()).contains("TODO.md");
        assertThat(msg.content()).contains("断点续传");
    }

    @Test
    void includesAgentsMdWhenGuideLoaderReturnsContent() {
        WorkspaceGuideLoader loader = root -> Optional.of("# Project Guide\nUse Java 21.");
        PromptComposer composer = new PromptComposer(false, loader, null);
        Message msg = composer.compose("/workspace");

        assertThat(msg.content()).contains("项目专属指南");
        assertThat(msg.content()).contains("AGENTS.md");
        assertThat(msg.content()).contains("# Project Guide");
        assertThat(msg.content()).contains("Use Java 21.");
    }

    @Test
    void skipsAgentsMdWhenGuideLoaderReturnsEmpty() {
        WorkspaceGuideLoader loader = root -> Optional.empty();
        PromptComposer composer = new PromptComposer(false, loader, null);
        Message msg = composer.compose("/workspace");

        assertThat(msg.content()).doesNotContain("项目专属指南");
    }

    @Test
    void skipsAgentsMdWhenGuideLoaderIsNull() {
        PromptComposer composer = new PromptComposer(false, null, null);
        Message msg = composer.compose("/workspace");

        assertThat(msg.content()).doesNotContain("项目专属指南");
    }

    @Test
    void includesSkillsPlaceholderWhenSkillLoaderIsNull() {
        PromptComposer composer = new PromptComposer(false, null, null);
        Message msg = composer.compose("/workspace");

        assertThat(msg.content()).contains("Skills");
        assertThat(msg.content()).contains("后续版本启用");
    }

    @Test
    void includesSkillsPlaceholderWhenSkillLoaderReturnsEmpty() {
        SkillLoader loader = root -> Optional.empty();
        PromptComposer composer = new PromptComposer(false, null, loader);
        Message msg = composer.compose("/workspace");

        assertThat(msg.content()).contains("Skills");
        assertThat(msg.content()).contains("后续版本启用");
    }

    @Test
    void includesSkillsContentWhenSkillLoaderReturnsContent() {
        SkillLoader loader = root -> Optional.of("#### Skill: Test\nTrigger: test\n");
        PromptComposer composer = new PromptComposer(false, null, loader);
        Message msg = composer.compose("/workspace");

        assertThat(msg.content()).contains("Skill: Test");
        assertThat(msg.content()).contains("Trigger: test");
        assertThat(msg.content()).doesNotContain("后续版本启用");
    }

    @Test
    void rejectsNullWorkspaceRoot() {
        PromptComposer composer = new PromptComposer();
        assertThatThrownBy(() -> composer.compose(null))
            .isInstanceOf(com.tinyclaw.domain.common.TinyClawDomainException.class)
            .hasMessageContaining("workspaceRoot");
    }

    @Test
    void rejectsBlankWorkspaceRoot() {
        PromptComposer composer = new PromptComposer();
        assertThatThrownBy(() -> composer.compose("  "))
            .isInstanceOf(com.tinyclaw.domain.common.TinyClawDomainException.class)
            .hasMessageContaining("workspaceRoot");
    }

    @Test
    void contentOrderIsCorrect(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "# Guide");
        WorkspaceGuideLoader guideLoader = new com.tinyclaw.adapters.filesystem.FilesystemWorkspaceGuideLoader();
        SkillLoader skillLoader = root -> Optional.of("# Skills\nTest skill.");
        PromptComposer composer = new PromptComposer(true, guideLoader, skillLoader);
        Message msg = composer.compose(workspace.toString());

        String content = msg.content();
        int identityPos = content.indexOf("核心身份");
        int disciplinePos = content.indexOf("核心纪律");
        int planPos = content.indexOf("Plan Mode");
        int agentsPos = content.indexOf("项目专属指南");
        int skillsPos = content.indexOf("Skills");

        assertThat(identityPos).isGreaterThanOrEqualTo(0);
        assertThat(disciplinePos).isGreaterThan(identityPos);
        assertThat(planPos).isGreaterThan(disciplinePos);
        assertThat(agentsPos).isGreaterThan(planPos);
        assertThat(skillsPos).isGreaterThan(agentsPos);
    }

    @Test
    void coreIdentityContainsTinyClawName() {
        PromptComposer composer = new PromptComposer();
        Message msg = composer.compose("/workspace");

        assertThat(msg.content()).contains("TinyClaw");
        assertThat(msg.content()).doesNotContain("go-tiny-claw");
    }

    @Test
    void coreDisciplineContainsShellCommandNotBash() {
        PromptComposer composer = new PromptComposer();
        Message msg = composer.compose("/workspace");

        assertThat(msg.content()).contains("shell_command");
        assertThat(msg.content()).doesNotContain("bash");
    }

    @Test
    void coreDisciplineContainsSixRules() {
        PromptComposer composer = new PromptComposer();
        Message msg = composer.compose("/workspace");

        String content = msg.content();
        assertThat(content).contains("1.");
        assertThat(content).contains("2.");
        assertThat(content).contains("3.");
        assertThat(content).contains("4.");
        assertThat(content).contains("5.");
        assertThat(content).contains("6.");
        assertThat(content).contains("write_file");
        assertThat(content).contains("read_file");
        assertThat(content).contains("shell_command");
    }

    @Test
    void planModeContainsShellCommandNotBash() {
        PromptComposer composer = new PromptComposer(true, null, null);
        Message msg = composer.compose("/workspace");

        assertThat(msg.content()).contains("shell_command");
        assertThat(msg.content()).doesNotContain("bash");
    }

    @Test
    void planModeContainsWriteFileAndReadFileAndEditFile() {
        PromptComposer composer = new PromptComposer(true, null, null);
        Message msg = composer.compose("/workspace");

        assertThat(msg.content()).contains("write_file");
        assertThat(msg.content()).contains("read_file");
        assertThat(msg.content()).contains("edit_file");
    }

    @Test
    void defaultConstructorIsBackwardCompatible() {
        // 验证默认构造函数行为与修改前一致：Plan Mode 关闭，无外部加载
        PromptComposer composer = new PromptComposer();
        Message msg = composer.compose("/workspace");

        assertThat(msg.role()).isEqualTo(Role.SYSTEM);
        assertThat(msg.content()).contains("TinyClaw");
        assertThat(msg.content()).doesNotContain("Plan Mode");
        assertThat(msg.content()).doesNotContain("项目专属指南");
    }

    @Test
    void guideLoaderOnlyConstructorIsBackwardCompatible() {
        // 验证单参数 guideLoader 构造函数
        WorkspaceGuideLoader loader = root -> Optional.of("# Guide");
        PromptComposer composer = new PromptComposer(loader);
        Message msg = composer.compose("/workspace");

        assertThat(msg.content()).contains("项目专属指南");
        assertThat(msg.content()).doesNotContain("Plan Mode");
    }

    @Test
    void fullConstructorWithAllLoadersAndPlanMode() {
        WorkspaceGuideLoader guideLoader = root -> Optional.of("# Guide");
        SkillLoader skillLoader = root -> Optional.of("# Skill");
        PromptComposer composer = new PromptComposer(true, guideLoader, skillLoader);
        Message msg = composer.compose("/workspace");

        assertThat(msg.content()).contains("Plan Mode: ON");
        assertThat(msg.content()).contains("项目专属指南");
        assertThat(msg.content()).contains("# Skill");
    }
}

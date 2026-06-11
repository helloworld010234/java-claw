package com.tinyclaw.application.engine;

import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.ports.workspace.SkillLoader;
import com.tinyclaw.ports.workspace.WorkspaceGuideLoader;

import java.util.Optional;

/**
 * Constructs the system prompt for an agent run.
 *
 * <p>Builds a structured system prompt containing core identity, discipline
 * rules, optional Plan Mode instructions, dynamically loaded project
 * guides (AGENTS.md), and skills. All text fragments are immutable constants.</p>
 *
 * <p>Thread-safe: stateless after construction.</p>
 */
public class PromptComposer {

    // ========== 核心身份 ==========
    private static final String CORE_IDENTITY = """
        # 核心身份
        你名叫 TinyClaw，一个由驾驭工程驱动的骨灰级研发助手。
        你具备极简主义哲学，拒绝废话。你能通过系统提供的内置工具，创建、读取、修改和执行工作区中的代码。
        """;

    // ========== 核心纪律 ==========
    private static final String CORE_DISCIPLINE = """
        # 核心纪律 (CRITICAL)
        1. 如需检查文件是否存在，请使用 shell_command 的 ls 或 test -f，而不是对目录使用 read_file。
        2. 创建新文件时，务必使用 write_file，并同时提供 path 和 content 参数。
        3. 编辑文件前务必先读取现有文件，以理解上下文。
        4. 无论何时你需要写代码或创建文件，都要直接使用 write_file 工具。
        5. 遇到工具执行报错时，仔细阅读 stderr，尝试自己修正命令并重试。
        6. 始终用中文回复，以便传达你的进展和想法。
        """;

    // ========== Plan Mode 规范 ==========
    private static final String PLAN_MODE_HEADER = """
        # 长程任务与状态外部化强制规范 (Plan Mode: ON)
        
        !!! 警告：本模式下，你绝对不能依赖自己的短期记忆。你必须将所有的架构思路和执行进度持久化到物理文件中。 !!!
        """;

    private static final String PLAN_MODE_STEP1 = """
        **[STEP 1: 强制环境嗅探 (Bootstrapping)]**
        - 收到指令后，你必须第一时间使用 shell_command (如: `ls -la`) 检查当前工作区根目录下是否已经存在 `PLAN.md` 和 `TODO.md`。
        - **分支 A (全新任务)**：如果这两个文件不存在，说明这是一个全新的任务。你必须使用 write_file 依次创建它们：
          1. 先创建 `PLAN.md`，写下你的理解、架构设计、技术选型。
          2. 再创建 `TODO.md`，拆解出具体的可执行步骤（使用标准的 Markdown Checkbox 格式，如 `- [ ] 步骤1`）。
        - **分支 B (断点续传/任务唤醒)**：如果这两个文件已经存在，**绝对不要覆盖它们！** 这意味着系统刚刚重启，或者人类接管了进度。你必须立即使用 read_file 仔细阅读 `PLAN.md` 了解全局目标，并阅读 `TODO.md` 寻找第一个未被打勾的 `- [ ]` 任务，从那里直接继续干活。
        """;

    private static final String PLAN_MODE_STEP2 = """
        **[STEP 2: 严格的单步执行与实时打勾]**
        - 开始执行 `TODO.md` 中未完成的任务。
        - **强制约束**：每当你通过 write_file 或 shell_command 真正完成了一个子任务后，你**必须立即停下来**，优先使用 edit_file 工具（或 shell_command 的 sed 命令），将 `TODO.md` 中对应的行修改为 `- [x]`。
        - 绝对不允许"一口气写完所有代码最后再打勾"。做完一步，必须打勾一步！
        """;

    private static final String PLAN_MODE_STEP3 = """
        **[STEP 3: 迷失时的自救]**
        - 如果你在执行中遇到了报错，或者不知道下一步该干嘛了，立即使用 read_file 重新读取 `TODO.md` 确认自己的位置。
        """;

    // ========== AGENTS.md 包装器 ==========
    private static final String AGENTS_MD_HEADER = "\n# 项目专属指南 (来自 AGENTS.md)\n```markdown\n";
    private static final String AGENTS_MD_FOOTER = "\n```\n";

    // ========== Skills 占位 ==========
    private static final String SKILLS_HEADER = "\n# 可用专业技能 (Agent Skills)\n";
    private static final String SKILLS_PLACEHOLDER = "（Skills 加载器将在后续版本启用，当前请依据核心纪律执行任务。）\n";

    private final boolean planMode;
    private final WorkspaceGuideLoader guideLoader;
    private final SkillLoader skillLoader;

    /**
     * 创建默认配置的 PromptComposer（Plan Mode 关闭，不加载外部指南和 Skills）。
     */
    public PromptComposer() {
        this(false, null, null);
    }

    /**
     * 创建 PromptComposer（Plan Mode 关闭，指定 guideLoader）。
     *
     * @param guideLoader 项目指南加载器，可为 null
     */
    public PromptComposer(WorkspaceGuideLoader guideLoader) {
        this(false, guideLoader, null);
    }

    /**
     * 创建 PromptComposer。
     *
     * @param planMode     是否启用 Plan Mode 规范
     * @param guideLoader  项目指南加载器，可为 null
     * @param skillLoader  技能加载器，可为 null
     */
    public PromptComposer(boolean planMode, WorkspaceGuideLoader guideLoader, SkillLoader skillLoader) {
        this.planMode = planMode;
        this.guideLoader = guideLoader;
        this.skillLoader = skillLoader;
    }

    /**
     * 构建完整的 system prompt。
     *
     * @param workspaceRoot 工作区根目录绝对路径，用于加载 AGENTS.md；必须非空非 blank
     * @return system 角色消息，永远不会为 null
     */
    public Message compose(String workspaceRoot) {
        DomainGuards.requireNonBlank(workspaceRoot, "workspaceRoot");

        StringBuilder prompt = new StringBuilder(4096);
        prompt.append(CORE_IDENTITY).append('\n');
        prompt.append("当前工作区: ").append(workspaceRoot).append("\n\n");
        prompt.append(CORE_DISCIPLINE).append('\n');

        if (planMode) {
            prompt.append(PLAN_MODE_HEADER).append('\n');
            prompt.append(PLAN_MODE_STEP1).append('\n');
            prompt.append(PLAN_MODE_STEP2).append('\n');
            prompt.append(PLAN_MODE_STEP3).append('\n');
        }

        appendAgentsGuide(prompt, workspaceRoot);
        appendSkills(prompt, workspaceRoot);

        return Message.system(prompt.toString());
    }

    private void appendAgentsGuide(StringBuilder prompt, String workspaceRoot) {
        if (guideLoader == null) {
            return;
        }
        Optional<String> guide = guideLoader.loadGuide(workspaceRoot);
        guide.ifPresent(content -> {
            prompt.append(AGENTS_MD_HEADER)
                  .append(content)
                  .append(AGENTS_MD_FOOTER)
                  .append('\n');
        });
    }

    private void appendSkills(StringBuilder prompt, String workspaceRoot) {
        prompt.append(SKILLS_HEADER);
        if (skillLoader == null) {
            prompt.append(SKILLS_PLACEHOLDER);
            return;
        }
        Optional<String> skills = skillLoader.loadSkills(workspaceRoot);
        if (skills.isPresent()) {
            prompt.append(skills.get()).append('\n');
        } else {
            prompt.append(SKILLS_PLACEHOLDER);
        }
    }
}

package com.tinyclaw.ports.engine;

import com.tinyclaw.ports.reporter.Reporter;
import com.tinyclaw.ports.tool.ToolCatalog;

/**
 * 子 Agent 运行器端口。
 *
 * <p>主 Agent 通过此端口委派探索任务给子 Agent。子 Agent 在受限环境中运行，
 * 只能访问只读工具集，且最多运行固定轮数。</p>
 */
public interface SubagentRunner {

    /**
     * 运行子 Agent 执行探索任务。
     *
     * @param taskPrompt          给子 Agent 的明确探索指令，必须非空非 blank
     * @param readOnlyCatalog     只读工具目录，必须非空
     * @param reporter            进度报告器，可为 null
     * @param workDir             工作区根目录，必须非空非 blank
     * @return 子 Agent 的探索报告摘要，永远不会为 null
     */
    String runSub(String taskPrompt, ToolCatalog readOnlyCatalog, Reporter reporter, String workDir);
}

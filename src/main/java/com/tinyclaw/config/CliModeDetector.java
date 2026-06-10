package com.tinyclaw.config;

import java.util.Set;

/**
 * CLI 模式检测器。
 *
 * <p>纯工具类，不依赖 Spring。通过扫描命令行参数判断用户意图是启动 CLI 还是 Web 服务。</p>
 */
public final class CliModeDetector {

    private static final Set<String> KNOWN_COMMANDS = Set.of("run", "tool", "runs", "approvals");

    private CliModeDetector() {
        // utility class
    }

    /**
     * 判断传入的参数是否表示 CLI 模式。
     *
     * <p>扫描 args，找到第一个不以 {@code --} 开头的参数，若它匹配已知的 CLI 子命令（如 {@code run}），
     * 则返回 {@code true}。</p>
     */
    public static boolean isCliMode(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if (arg != null && !arg.startsWith("--") && KNOWN_COMMANDS.contains(arg)) {
                return true;
            }
        }
        return false;
    }
}

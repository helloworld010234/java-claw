package com.tinyclaw.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 启动参数规范化工具。
 *
 * <p>在 Spring Boot 启动前执行，将空格分隔的配置参数（如 {@code --key value}）
 * 转换为 Spring Boot 可识别的等号形式（{@code --key=value}）。</p>
 *
 * <p>只处理已知的 Boot/应用配置前缀，不触碰 Picocli 业务参数。</p>
 */
public final class BootstrapArgs {

    private static final Set<String> CONFIG_PREFIXES = Set.of(
        "--spring.", "--server.", "--management.", "--logging.", "--tinyclaw."
    );

    private BootstrapArgs() {
        // utility class
    }

    /**
     * 将空格分隔的配置参数规范化为等号形式。
     *
     * @param args 原始命令行参数，可能为 {@code null}
     * @return 规范化后的参数数组；{@code null} 输入返回空数组
     */
    public static String[] normalize(String[] args) {
        if (args == null) {
            return new String[0];
        }

        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < args.length) {
            String arg = args[i];
            if (isConfigProperty(arg) && !arg.contains("=")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    result.add(arg + "=" + args[i + 1]);
                    i += 2;
                    continue;
                }
            }
            result.add(arg);
            i++;
        }
        return result.toArray(new String[0]);
    }

    private static boolean isConfigProperty(String arg) {
        if (arg == null) {
            return false;
        }
        for (String prefix : CONFIG_PREFIXES) {
            if (arg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}

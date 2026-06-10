package com.tinyclaw.config;

import com.tinyclaw.adapters.cli.RootCommand;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * CLI 启动器：将命令行参数委托给 Picocli 执行。
 *
 * <p>作为 {@link CommandLineRunner} 在 Spring Boot 启动后运行；
 * 同时实现 {@link ExitCodeGenerator} 以支持进程退出码传递。</p>
 *
 * <p>会自动过滤掉 Spring Boot 属性参数，只将真正的 CLI 参数传递给 Picocli。</p>
 */
@Component
public class CliRunner implements CommandLineRunner, ExitCodeGenerator {

    private static final Set<String> KNOWN_COMMANDS = Set.of("run", "tool", "runs");
    private static final Set<String> SPRING_PREFIXES = Set.of(
        "--spring.", "--server.", "--management.", "--logging."
    );

    private final RootCommand rootCommand;
    private final CommandLine.IFactory factory;
    private int exitCode = 0;

    public CliRunner(RootCommand rootCommand, CommandLine.IFactory factory) {
        this.rootCommand = rootCommand;
        this.factory = factory;
    }

    @Override
    public void run(String... args) {
        String[] cliArgs = filterSpringArgs(args);

        if (containsCliCommand(cliArgs)) {
            exitCode = new CommandLine(rootCommand, factory).execute(cliArgs);
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    /**
     * 仅供测试使用：重置 exitCode。
     */
    void resetForTest() {
        this.exitCode = 0;
    }

    private static String[] filterSpringArgs(String[] args) {
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < args.length) {
            String arg = args[i];
            if (isSpringBootProperty(arg)) {
                // Handle space-separated format: --key value
                if (!arg.contains("=") && i + 1 < args.length) {
                    i++;
                }
                i++;
                continue;
            }
            result.add(arg);
            i++;
        }
        return result.toArray(new String[0]);
    }

    private static boolean isSpringBootProperty(String arg) {
        if (arg == null) {
            return false;
        }
        for (String prefix : SPRING_PREFIXES) {
            if (arg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsCliCommand(String[] args) {
        for (String arg : args) {
            if (arg != null && !arg.startsWith("--") && KNOWN_COMMANDS.contains(arg)) {
                return true;
            }
        }
        return false;
    }
}

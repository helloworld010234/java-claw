package com.tinyclaw.config;

import com.tinyclaw.adapters.cli.RootCommand;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import java.util.Arrays;

/**
 * CLI 启动器：将命令行参数委托给 Picocli 执行。
 *
 * <p>作为 {@link CommandLineRunner} 在 Spring Boot 启动后运行；
 * 同时实现 {@link ExitCodeGenerator} 以支持进程退出码传递。</p>
 *
 * <p>会自动过滤掉 Spring Boot 属性参数（如 {@code --spring.profiles.active=test}），
 * 只将真正的 CLI 参数传递给 Picocli。</p>
 */
@Component
public class CliRunner implements CommandLineRunner, ExitCodeGenerator {

    private final RootCommand rootCommand;
    private final CommandLine.IFactory factory;
    private int exitCode = 0;

    public CliRunner(RootCommand rootCommand, CommandLine.IFactory factory) {
        this.rootCommand = rootCommand;
        this.factory = factory;
    }

    @Override
    public void run(String... args) {
        String[] cliArgs = Arrays.stream(args)
            .filter(arg -> !arg.startsWith("--spring."))
            .toArray(String[]::new);

        if (cliArgs.length > 0) {
            exitCode = new CommandLine(rootCommand, factory).execute(cliArgs);
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}

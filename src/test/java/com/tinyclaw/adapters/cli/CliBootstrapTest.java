package com.tinyclaw.adapters.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI bootstrap 集成测试。
 *
 * <p>验证 Spring 管理的 Picocli root command 能正确解析并执行 run 子命令。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class CliBootstrapTest {

    @Autowired
    private RootCommand rootCommand;

    @Autowired
    private CommandLine.IFactory factory;

    @Test
    void rootCommandShouldContainRunSubcommand() {
        CommandLine cmd = new CommandLine(rootCommand, factory);
        assertThat(cmd.getSubcommands()).containsKey("run");
    }

    @Test
    void runViaCommandLineShouldSucceed(@TempDir Path tempDir) {
        CommandLine cmd = new CommandLine(rootCommand, factory);
        int exitCode = cmd.execute(
            "run",
            "--prompt", "Hello",
            "--dir", tempDir.toString(),
            "--session", "smoke"
        );

        assertThat(exitCode).isZero();
    }
}

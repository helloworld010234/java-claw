package com.tinyclaw.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CliRunner 集成测试。
 *
 * <p>验证过滤 Spring Boot 参数、识别 CLI 子命令、返回 exit code 等行为。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class CliRunnerTest {

    @Autowired
    private CliRunner cliRunner;

    @BeforeEach
    void reset() {
        cliRunner.resetForTest();
    }

    @Test
    void withCliCommandShouldExecuteAndReturnZero(@TempDir Path tempDir) {
        cliRunner.run(
            "run", "--prompt", "Hello",
            "--dir", tempDir.toString(),
            "--session", "smoke",
            "--spring.profiles.active=test",
            "--spring.main.web-application-type=none"
        );
        assertThat(cliRunner.getExitCode()).isZero();
    }

    @Test
    void withOnlyServerPortShouldNotExecuteAndReturnZero() {
        cliRunner.run("--server.port=0");
        assertThat(cliRunner.getExitCode()).isZero();
    }

    @Test
    void withOnlySpringArgsShouldNotExecuteAndReturnZero() {
        cliRunner.run(
            "--spring.profiles.active=test",
            "--management.endpoints.web.exposure.include=*",
            "--logging.level.org.springframework=debug"
        );
        assertThat(cliRunner.getExitCode()).isZero();
    }

    @Test
    void withSpaceSeparatedSpringArgsShouldFilterCorrectly(@TempDir Path tempDir) {
        cliRunner.run(
            "run", "--prompt", "Hello",
            "--dir", tempDir.toString(),
            "--session", "smoke",
            "--spring.profiles.active", "test",
            "--server.port", "0"
        );
        assertThat(cliRunner.getExitCode()).isZero();
    }

    @Test
    void withTinyclawApprovalRequiredToolsEqualsFormShouldFilterAndNotFail(@TempDir Path tempDir) {
        cliRunner.run(
            "run", "--prompt", "Hello",
            "--dir", tempDir.toString(),
            "--session", "smoke",
            "--spring.profiles.active=test",
            "--spring.main.web-application-type=none",
            "--tinyclaw.approval.required-tools=shell_command"
        );
        assertThat(cliRunner.getExitCode()).isNotEqualTo(2);
    }

    @Test
    void withTinyclawApprovalRequiredToolsSpaceFormShouldFilterAndNotFail(@TempDir Path tempDir) {
        cliRunner.run(
            "run", "--prompt", "Hello",
            "--dir", tempDir.toString(),
            "--session", "smoke",
            "--spring.profiles.active", "test",
            "--spring.main.web-application-type", "none",
            "--tinyclaw.approval.required-tools", "shell_command"
        );
        assertThat(cliRunner.getExitCode()).isNotEqualTo(2);
    }

    @Test
    void businessArgsShouldNotBeFiltered(@TempDir Path tempDir) {
        cliRunner.run(
            "run", "--prompt", "Hello",
            "--dir", tempDir.toString(),
            "--session", "smoke",
            "--engine", "fake",
            "--spring.profiles.active=test"
        );
        assertThat(cliRunner.getExitCode()).isNotEqualTo(2);
    }

    @Test
    void toolWithUnknownNameShouldReturnOne(@TempDir Path tempDir) {
        cliRunner.run(
            "tool", "--name", "unknown", "--args", "{}",
            "--dir", tempDir.toString(),
            "--call-id", "call-1",
            "--spring.profiles.active=test",
            "--spring.main.web-application-type=none"
        );
        assertThat(cliRunner.getExitCode()).isEqualTo(1);
    }

    @Test
    void runWithPlanFileSuccessShouldReturnZero(@TempDir Path tempDir) throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("notes.txt"), "hello");
        java.nio.file.Files.writeString(tempDir.resolve("plan.json"), """
            {
              "stopOnError": true,
              "steps": [
                {"id": "read-notes", "tool": "read_file", "args": {"path": "notes.txt"}}
              ]
            }
            """);

        cliRunner.run(
            "run", "--prompt", "Read notes",
            "--dir", tempDir.toString(),
            "--session", "smoke-run",
            "--plan-file", tempDir.resolve("plan.json").toString(),
            "--spring.profiles.active=test",
            "--spring.main.web-application-type=none"
        );
        assertThat(cliRunner.getExitCode()).isZero();
    }

    @Test
    void runWithPlanFileToolFailureShouldReturnOne(@TempDir Path tempDir) throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("fail-plan.json"), """
            {
              "stopOnError": true,
              "steps": [
                {"id": "missing-read", "tool": "read_file", "args": {"path": "missing.txt"}}
              ]
            }
            """);

        cliRunner.run(
            "run", "--prompt", "Fail fast",
            "--dir", tempDir.toString(),
            "--session", "smoke-fail",
            "--plan-file", tempDir.resolve("fail-plan.json").toString(),
            "--spring.profiles.active=test",
            "--spring.main.web-application-type=none"
        );
        assertThat(cliRunner.getExitCode()).isEqualTo(1);
    }

    @Test
    void runWithPlanFileMissingShouldReturnTwo(@TempDir Path tempDir) {
        cliRunner.run(
            "run", "--prompt", "Hello",
            "--dir", tempDir.toString(),
            "--session", "smoke",
            "--plan-file", tempDir.resolve("missing.json").toString(),
            "--spring.profiles.active=test",
            "--spring.main.web-application-type=none"
        );
        assertThat(cliRunner.getExitCode()).isEqualTo(2);
    }

    @Test
    void runWithEngineFakeToolFailureShouldReturnOne(@TempDir Path tempDir) {
        cliRunner.run(
            "run", "--prompt", "read missing",
            "--dir", tempDir.toString(),
            "--session", "smoke-read-fail",
            "--engine", "fake",
            "--spring.profiles.active=test",
            "--spring.main.web-application-type=none"
        );
        assertThat(cliRunner.getExitCode()).isEqualTo(1);
    }

    @Test
    void withMixedConfigPrefixesShouldFilterAll(@TempDir Path tempDir) {
        cliRunner.run(
            "run", "--prompt", "Hello",
            "--dir", tempDir.toString(),
            "--session", "smoke",
            "--spring.profiles.active=test",
            "--server.port=0",
            "--management.endpoints.web.exposure.include=*",
            "--logging.level.org.springframework=debug",
            "--tinyclaw.approval.required-tools=shell_command"
        );
        assertThat(cliRunner.getExitCode()).isZero();
    }
}

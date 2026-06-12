package com.tinyclaw.adapters.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.tinyclaw.adapters.benchmark.NoOpValidationCommandRunner;
import com.tinyclaw.adapters.reporter.NoOpReporter;
import com.tinyclaw.adapters.session.InMemorySessionService;
import com.tinyclaw.adapters.tools.command.ShellCommandTool;
import com.tinyclaw.adapters.tools.filesystem.EditFileTool;
import com.tinyclaw.adapters.tools.filesystem.ReadFileTool;
import com.tinyclaw.adapters.tools.filesystem.WriteFileTool;
import com.tinyclaw.application.benchmark.BenchmarkSuite;
import com.tinyclaw.application.benchmark.FakeValidationCommandRunner;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.PromptComposer;
import com.tinyclaw.application.run.AgentRunExecutionService;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.config.AgentProperties;
import com.tinyclaw.config.TinyClawModelProperties;
import com.tinyclaw.ports.benchmark.GoTestResult;
import com.tinyclaw.ports.benchmark.GoTestStatus;
import com.tinyclaw.ports.benchmark.ValidationCommandRunnerPort;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmResponse;
import com.tinyclaw.ports.tool.AgentTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkCommandTest {

    @TempDir
    Path tempDir;

    private BenchmarkCommand command;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
        List<AgentTool> tools = List.of(
            new ReadFileTool(),
            new WriteFileTool(),
            new EditFileTool(),
            new ShellCommandTool()
        );
        LlmGateway dummyLlm = request -> new LlmResponse("", List.of(), null);
        InMemorySessionService sessionService = new InMemorySessionService();
        AgentEngine agentEngine = new AgentEngine(
            dummyLlm,
            new ToolRegistry(tools),
            new PromptComposer(),
            new NoOpReporter(),
            sessionService
        );
        command = new BenchmarkCommand(
            new AgentRunExecutionService(null, null, sessionService, new ObjectMapper(), new NoOpReporter()),
            agentEngine,
            new AgentProperties(),
            new TinyClawModelProperties(),
            null,
            tools,
            new com.tinyclaw.adapters.observability.AgentMetrics(new SimpleMeterRegistry()),
            Optional.empty(),
            new NoOpValidationCommandRunner()
        );
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out));
        System.setErr(new PrintStream(err));
    }

    private void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private CommandLine commandLine() {
        return new CommandLine(command);
    }

    @Test
    void benchFakeRunsAllCasesAndPasses() {
        int exitCode = commandLine().execute(
            "--engine", "fake",
            "--workspace-root", tempDir.resolve("workspaces").toString()
        );
        restoreStreams();

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("case: test_001_edit");
        assertThat(output).contains("case: test_002_code_gen");
        assertThat(output).contains("status: PASSED");
        assertThat(output).contains("overall: PASSED");
    }

    @Test
    void benchFakeSingleCaseRunsAndPasses() {
        int exitCode = commandLine().execute(
            "--engine", "fake",
            "--case", "test_001_edit",
            "--workspace-root", tempDir.resolve("workspaces").toString()
        );
        restoreStreams();

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("case: test_001_edit");
        assertThat(output).doesNotContain("case: test_002_code_gen");
    }

    @Test
    void benchInvalidEngineReturnsTwo() {
        int exitCode = commandLine().execute(
            "--engine", "magic",
            "--workspace-root", tempDir.resolve("workspaces").toString()
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("Invalid engine");
    }

    @Test
    void benchUnknownCaseReturnsTwo() {
        int exitCode = commandLine().execute(
            "--engine", "fake",
            "--case", "no-such-case",
            "--workspace-root", tempDir.resolve("workspaces").toString()
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    void benchFailingCaseReturnsOneAndPrintsReason() {
        int exitCode = commandLine().execute(
            "--engine", "fake",
            "--case", "test_003_fail",
            "--workspace-root", tempDir.resolve("workspaces").toString()
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(1);
        String output = out.toString();
        assertThat(output).contains("case: test_003_fail");
        assertThat(output).contains("status: FAILED");
        assertThat(output).contains("error:");
        assertThat(output).contains("Intentional validation failure");
    }

    @Test
    void benchRealDisabledReturnsTwo() {
        TinyClawModelProperties properties = new TinyClawModelProperties();
        properties.setEnabled(false);
        BenchmarkCommand realCommand = createCommandWithProperties(properties, Optional.empty(), new NoOpValidationCommandRunner());

        int exitCode = new CommandLine(realCommand).execute(
            "--engine", "real",
            "--workspace-root", tempDir.resolve("workspaces").toString()
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("not enabled");
    }

    @Test
    void benchRealMissingKeyReturnsTwo() {
        TinyClawModelProperties properties = new TinyClawModelProperties();
        properties.setEnabled(true);
        properties.setApiKey("");
        BenchmarkCommand realCommand = createCommandWithProperties(properties, Optional.empty(), new NoOpValidationCommandRunner());

        int exitCode = new CommandLine(realCommand).execute(
            "--engine", "real",
            "--workspace-root", tempDir.resolve("workspaces").toString()
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("API key");
    }

    @Test
    void benchRealEnabledUsesInjectedGateway() {
        AtomicBoolean called = new AtomicBoolean(false);
        LlmGateway realGateway = request -> {
            called.set(true);
            return new LlmResponse("Real benchmark response", List.of(), null);
        };
        TinyClawModelProperties properties = new TinyClawModelProperties();
        properties.setEnabled(true);
        properties.setApiKey("sk-test");
        BenchmarkCommand realCommand = createCommandWithProperties(properties, Optional.of(realGateway), new NoOpValidationCommandRunner());

        int exitCode = new CommandLine(realCommand).execute(
            "--engine", "real",
            "--case", "test_003_fail",
            "--workspace-root", tempDir.resolve("workspaces").toString()
        );
        restoreStreams();

        assertThat(called).isTrue();
        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    void benchFakeDoesNotCallRealGateway() {
        AtomicBoolean called = new AtomicBoolean(false);
        LlmGateway realGateway = request -> {
            called.set(true);
            return new LlmResponse("Real benchmark response", List.of(), null);
        };
        TinyClawModelProperties properties = new TinyClawModelProperties();
        properties.setEnabled(true);
        properties.setApiKey("sk-test");
        BenchmarkCommand fakeCommand = createCommandWithProperties(properties, Optional.of(realGateway), new NoOpValidationCommandRunner());

        int exitCode = new CommandLine(fakeCommand).execute(
            "--engine", "fake",
            "--case", "test_001_edit",
            "--workspace-root", tempDir.resolve("workspaces").toString()
        );
        restoreStreams();

        assertThat(called).isFalse();
        assertThat(exitCode).isZero();
    }

    @Test
    void benchGoTestSkippedIsReportedWithoutFailing() {
        ValidationCommandRunnerPort runner = FakeValidationCommandRunner.skipped("Go not installed");
        BenchmarkCommand cmd = createCommandWithProperties(new TinyClawModelProperties(), Optional.empty(), runner);

        int exitCode = new CommandLine(cmd).execute(
            "--engine", "fake",
            "--go-test",
            "--workspace-root", tempDir.resolve("workspaces").toString()
        );
        restoreStreams();

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("case: test_002_code_gen");
        assertThat(output).contains("Skipped: Go not installed");
    }

    @Test
    void benchGoTestPassedIsReported() {
        ValidationCommandRunnerPort runner = FakeValidationCommandRunner.passed("ok\n");
        BenchmarkCommand cmd = createCommandWithProperties(new TinyClawModelProperties(), Optional.empty(), runner);

        int exitCode = new CommandLine(cmd).execute(
            "--engine", "fake",
            "--go-test",
            "--workspace-root", tempDir.resolve("workspaces").toString()
        );
        restoreStreams();

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("case: test_002_code_gen");
        assertThat(output).contains("goTestOutput: |");
        assertThat(output).contains("ok");
    }

    @Test
    void benchGoTestFailedFailsTheCase() {
        ValidationCommandRunnerPort runner = FakeValidationCommandRunner.failed("exit code 1", "--- FAIL: TestMultiply");
        BenchmarkCommand cmd = createCommandWithProperties(new TinyClawModelProperties(), Optional.empty(), runner);

        int exitCode = new CommandLine(cmd).execute(
            "--engine", "fake",
            "--go-test",
            "--workspace-root", tempDir.resolve("workspaces").toString()
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(1);
        String output = out.toString();
        assertThat(output).contains("case: test_002_code_gen");
        assertThat(output).contains("status: FAILED");
        assertThat(output).contains("error: exit code 1");
        assertThat(output).contains("--- FAIL: TestMultiply");
    }

    @Test
    void benchGoTestLongOutputIsTruncatedInCli() {
        String longOutput = "line\n".repeat(20);
        ValidationCommandRunnerPort runner = FakeValidationCommandRunner.passed(longOutput);
        BenchmarkCommand cmd = createCommandWithProperties(new TinyClawModelProperties(), Optional.empty(), runner);

        int exitCode = new CommandLine(cmd).execute(
            "--engine", "fake",
            "--go-test",
            "--workspace-root", tempDir.resolve("workspaces").toString()
        );
        restoreStreams();

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("goTestOutput: |");
        assertThat(output).contains("(15 more lines)");
    }

    private BenchmarkCommand createCommandWithProperties(TinyClawModelProperties properties,
                                                         Optional<LlmGateway> realGateway,
                                                         ValidationCommandRunnerPort runner) {
        List<AgentTool> tools = List.of(
            new ReadFileTool(),
            new WriteFileTool(),
            new EditFileTool(),
            new ShellCommandTool()
        );
        LlmGateway dummyLlm = request -> new LlmResponse("", List.of(), null);
        InMemorySessionService sessionService = new InMemorySessionService();
        AgentEngine agentEngine = new AgentEngine(
            dummyLlm,
            new ToolRegistry(tools),
            new PromptComposer(),
            new NoOpReporter(),
            sessionService
        );
        return new BenchmarkCommand(
            new AgentRunExecutionService(null, null, sessionService, new ObjectMapper(), new NoOpReporter()),
            agentEngine,
            new AgentProperties(),
            properties,
            null,
            tools,
            new com.tinyclaw.adapters.observability.AgentMetrics(new SimpleMeterRegistry()),
            realGateway,
            runner
        );
    }
}

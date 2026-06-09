package com.tinyclaw.adapters.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.reporter.NoOpReporter;
import com.tinyclaw.adapters.session.InMemorySessionService;
import com.tinyclaw.adapters.tools.filesystem.EditFileTool;
import com.tinyclaw.adapters.tools.filesystem.ReadFileTool;
import com.tinyclaw.adapters.tools.filesystem.WriteFileTool;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.PromptComposer;
import com.tinyclaw.application.run.ScriptedRunExecutor;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunCommandTest {

    @TempDir
    Path tempDir;

    private RunCommand command;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
        ToolRegistry registry = new ToolRegistry(List.of(
            new ReadFileTool(),
            new WriteFileTool(),
            new EditFileTool()
        ));
        LlmGateway dummyLlm = request -> new LlmResponse("", List.of(), null);
        AgentEngine agentEngine = new AgentEngine(
            dummyLlm, registry, new PromptComposer(), new NoOpReporter(), new InMemorySessionService()
        );
        command = new RunCommand(
            new ScriptedRunExecutor(registry, new ObjectMapper()),
            agentEngine,
            new ObjectMapper()
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
    void runWithoutPlanFileReturnsZero() {
        int exitCode = commandLine().execute(
            "--prompt", "Hello agent",
            "--dir", tempDir.toString(),
            "--session", "test-session-001"
        );
        restoreStreams();

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("sessionId: test-session-001");
        assertThat(out.toString()).contains("workspace:");
        assertThat(out.toString()).contains("prompt: Hello agent");
    }

    @Test
    void runWithoutPromptShouldFail() {
        int exitCode = commandLine().execute(
            "--dir", tempDir.toString()
        );
        restoreStreams();

        assertThat(exitCode).isNotZero();
    }

    @Test
    void runWithNonExistentDirShouldFail() {
        int exitCode = commandLine().execute(
            "--prompt", "Hello",
            "--dir", "/nonexistent/path/12345"
        );
        restoreStreams();

        assertThat(exitCode).isNotZero();
    }

    @Test
    void runWithValidPlanFileSucceeds() throws IOException {
        String plan = """
            {
              "stopOnError": true,
              "steps": [
                {"id": "write-notes", "tool": "write_file", "args": {"path": "notes.txt", "content": "hello world", "overwrite": true}},
                {"id": "edit-notes", "tool": "edit_file", "args": {"path": "notes.txt", "oldText": "world", "newText": "agent"}},
                {"id": "read-notes", "tool": "read_file", "args": {"path": "notes.txt"}}
              ]
            }
            """;
        Path planFile = tempDir.resolve("plan.json");
        Files.writeString(planFile, plan);

        int exitCode = commandLine().execute(
            "--prompt", "Update notes",
            "--dir", tempDir.toString(),
            "--session", "run-1",
            "--plan-file", planFile.toString()
        );
        restoreStreams();

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("status: success");
        assertThat(out.toString()).contains("write-notes");
        assertThat(out.toString()).contains("edit-notes");
        assertThat(out.toString()).contains("read-notes");
        assertThat(Files.readString(tempDir.resolve("notes.txt"))).isEqualTo("hello agent");
    }

    @Test
    void runWithPlanFileToolFailureReturnsOne() throws IOException {
        String plan = """
            {
              "stopOnError": true,
              "steps": [
                {"id": "missing-read", "tool": "read_file", "args": {"path": "missing.txt"}}
              ]
            }
            """;
        Path planFile = tempDir.resolve("fail-plan.json");
        Files.writeString(planFile, plan);

        int exitCode = commandLine().execute(
            "--prompt", "Fail fast",
            "--dir", tempDir.toString(),
            "--session", "run-fail",
            "--plan-file", planFile.toString()
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(1);
        assertThat(out.toString()).contains("status: failed");
    }

    @Test
    void runWithMissingPlanFileReturnsTwo() {
        int exitCode = commandLine().execute(
            "--prompt", "Hello",
            "--dir", tempDir.toString(),
            "--plan-file", tempDir.resolve("missing.json").toString()
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    void runWithDirectoryAsPlanFileReturnsTwo() throws IOException {
        Path subDir = Files.createDirectory(tempDir.resolve("plan-dir"));

        int exitCode = commandLine().execute(
            "--prompt", "Hello",
            "--dir", tempDir.toString(),
            "--plan-file", subDir.toString()
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    void runWithInvalidJsonPlanFileReturnsTwo() throws IOException {
        Path planFile = tempDir.resolve("bad.json");
        Files.writeString(planFile, "{not json");

        int exitCode = commandLine().execute(
            "--prompt", "Hello",
            "--dir", tempDir.toString(),
            "--plan-file", planFile.toString()
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    void runWithEmptyStepsPlanFileReturnsTwo() throws IOException {
        String plan = """
            {
              "stopOnError": true,
              "steps": []
            }
            """;
        Path planFile = tempDir.resolve("empty.json");
        Files.writeString(planFile, plan);

        int exitCode = commandLine().execute(
            "--prompt", "Hello",
            "--dir", tempDir.toString(),
            "--plan-file", planFile.toString()
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    void runSummaryContainsAllFields() throws IOException {
        String plan = """
            {
              "stopOnError": true,
              "steps": [
                {"id": "read-notes", "tool": "read_file", "args": {"path": "notes.txt"}}
              ]
            }
            """;
        Path planFile = tempDir.resolve("plan.json");
        Files.writeString(planFile, plan);
        Files.writeString(tempDir.resolve("notes.txt"), "content");

        commandLine().execute(
            "--prompt", "Read",
            "--dir", tempDir.toString(),
            "--session", "run-1",
            "--plan-file", planFile.toString()
        );
        restoreStreams();

        String output = out.toString();
        assertThat(output).contains("sessionId:");
        assertThat(output).contains("workspace:");
        assertThat(output).contains("prompt:");
        assertThat(output).contains("planFile:");
        assertThat(output).contains("status:");
        assertThat(output).contains("steps:");
        assertThat(output).contains("id: read-notes");
        assertThat(output).contains("tool: read_file");
        assertThat(output).contains("error:");
        assertThat(output).contains("output:");
    }

    // --- engine fake tests ---

    @Test
    void engineFakeTextReplySucceeds() {
        int exitCode = commandLine().execute(
            "--prompt", "hello",
            "--dir", tempDir.toString(),
            "--session", "fake-session",
            "--engine", "fake"
        );
        restoreStreams();

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("mode: agent");
        assertThat(output).contains("status: success");
        assertThat(output).contains("turns: 1");
        assertThat(output).contains("session: fake-session");
    }

    @Test
    void engineFakeWriteToolSucceeds() {
        int exitCode = commandLine().execute(
            "--prompt", "write something",
            "--dir", tempDir.toString(),
            "--session", "fake-write",
            "--engine", "fake"
        );
        restoreStreams();

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("status: success");
        assertThat(out.toString()).contains("turns: 2");
    }

    @Test
    void engineFakeLlmFailureReturnsOne() {
        int exitCode = commandLine().execute(
            "--prompt", "fail now",
            "--dir", tempDir.toString(),
            "--session", "fake-fail",
            "--engine", "fake"
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(1);
        String output = out.toString();
        assertThat(output).contains("status: failed");
        assertThat(output).contains("error:");
    }

    @Test
    void planFileTakesPriorityOverEngineFake() throws IOException {
        String plan = """
            {
              "stopOnError": true,
              "steps": [
                {"id": "w1", "tool": "write_file", "args": {"path": "plan.txt", "content": "from-plan", "overwrite": true}}
              ]
            }
            """;
        Path planFile = tempDir.resolve("plan.json");
        Files.writeString(planFile, plan);

        int exitCode = commandLine().execute(
            "--prompt", "fail now",
            "--dir", tempDir.toString(),
            "--session", "priority-test",
            "--engine", "fake",
            "--plan-file", planFile.toString()
        );
        restoreStreams();

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("planFile:");
        assertThat(out.toString()).contains("Wrote file: plan.txt");
    }

    @Test
    void engineFakeWithNonExistentDirReturnsTwo() {
        int exitCode = commandLine().execute(
            "--prompt", "hello",
            "--dir", "/nonexistent/path/99999",
            "--engine", "fake"
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    void engineFakeToolFailureReturnsOne() {
        int exitCode = commandLine().execute(
            "--prompt", "read missing",
            "--dir", tempDir.toString(),
            "--session", "fake-read-fail",
            "--engine", "fake"
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(1);
        String output = out.toString();
        assertThat(output).contains("mode: agent");
        assertThat(output).contains("status: failed");
        assertThat(output).contains("error:");
    }

    @Test
    void invalidEngineReturnsTwo() {
        int exitCode = commandLine().execute(
            "--prompt", "hello",
            "--dir", tempDir.toString(),
            "--session", "invalid-engine",
            "--engine", "nope"
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("Invalid engine");
    }

    @Test
    void engineNoneUsesLegacyMode() {
        int exitCode = commandLine().execute(
            "--prompt", "hello",
            "--dir", tempDir.toString(),
            "--session", "engine-none",
            "--engine", "none"
        );
        restoreStreams();

        assertThat(exitCode).isZero();
        assertThat(out.toString()).doesNotContain("mode: agent");
        assertThat(out.toString()).contains("sessionId: engine-none");
    }

    @Test
    void planFileWithInvalidEngineReturnsTwo() throws IOException {
        String plan = """
            {
              "stopOnError": true,
              "steps": [
                {"id": "w1", "tool": "write_file", "args": {"path": "plan.txt", "content": "from-plan", "overwrite": true}}
              ]
            }
            """;
        Path planFile = tempDir.resolve("plan.json");
        Files.writeString(planFile, plan);

        int exitCode = commandLine().execute(
            "--prompt", "hello",
            "--dir", tempDir.toString(),
            "--session", "plan-invalid-engine",
            "--engine", "nope",
            "--plan-file", planFile.toString()
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("Invalid engine");
        assertThat(tempDir.resolve("plan.txt")).doesNotExist();
    }
}

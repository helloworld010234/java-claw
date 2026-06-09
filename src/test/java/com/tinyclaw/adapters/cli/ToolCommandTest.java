package com.tinyclaw.adapters.cli;

import com.tinyclaw.adapters.tools.filesystem.EditFileTool;
import com.tinyclaw.adapters.tools.filesystem.ReadFileTool;
import com.tinyclaw.adapters.tools.filesystem.WriteFileTool;
import com.tinyclaw.application.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
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

class ToolCommandTest {

    @TempDir
    Path tempDir;

    private ToolCommand command;
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
        command = new ToolCommand(registry);
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out));
        System.setErr(new PrintStream(err));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private CommandLine commandLine() {
        return new CommandLine(command);
    }

    @Test
    void readFileSuccessReturnsZero() throws IOException {
        Files.writeString(tempDir.resolve("notes.txt"), "hello");

        int exitCode = commandLine().execute(
            "--name", "read_file",
            "--args", "{\"path\":\"notes.txt\"}",
            "--dir", tempDir.toString(),
            "--call-id", "call-1"
        );

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("toolCallId: call-1");
        assertThat(out.toString()).contains("toolName: read_file");
        assertThat(out.toString()).contains("error: false");
        assertThat(out.toString()).contains("hello");
    }

    @Test
    void writeFileSuccessReturnsZero() {
        int exitCode = commandLine().execute(
            "--name", "write_file",
            "--args", "{\"path\":\"notes.txt\",\"content\":\"hello\",\"overwrite\":true}",
            "--dir", tempDir.toString(),
            "--call-id", "call-1"
        );

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("toolCallId: call-1");
        assertThat(out.toString()).contains("toolName: write_file");
        assertThat(out.toString()).contains("error: false");
    }

    @Test
    void editFileSuccessReturnsZero() throws IOException {
        Files.writeString(tempDir.resolve("notes.txt"), "hello world");

        int exitCode = commandLine().execute(
            "--name", "edit_file",
            "--args", "{\"path\":\"notes.txt\",\"oldText\":\"world\",\"newText\":\"agent\"}",
            "--dir", tempDir.toString(),
            "--call-id", "call-1"
        );

        assertThat(exitCode).isZero();
        assertThat(Files.readString(tempDir.resolve("notes.txt"))).isEqualTo("hello agent");
    }

    @Test
    void unknownToolReturnsOne() {
        int exitCode = commandLine().execute(
            "--name", "unknown",
            "--args", "{}",
            "--dir", tempDir.toString(),
            "--call-id", "call-1"
        );

        assertThat(exitCode).isEqualTo(1);
        assertThat(out.toString()).contains("error: true");
        assertThat(out.toString()).contains("Unknown tool");
    }

    @Test
    void missingNameReturnsNonZero() {
        int exitCode = commandLine().execute(
            "--args", "{}",
            "--dir", tempDir.toString()
        );

        assertThat(exitCode).isNotZero();
    }

    @Test
    void missingArgsReturnsNonZero() {
        int exitCode = commandLine().execute(
            "--name", "read_file",
            "--dir", tempDir.toString()
        );

        assertThat(exitCode).isNotZero();
    }

    @Test
    void nonExistentDirReturnsTwo() {
        int exitCode = commandLine().execute(
            "--name", "read_file",
            "--args", "{\"path\":\"notes.txt\"}",
            "--dir", "/nonexistent/path/12345",
            "--call-id", "call-1"
        );

        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    void fileAsDirReturnsTwo() throws IOException {
        Path file = tempDir.resolve("not-a-dir.txt");
        Files.writeString(file, "content");

        int exitCode = commandLine().execute(
            "--name", "read_file",
            "--args", "{\"path\":\"notes.txt\"}",
            "--dir", file.toString(),
            "--call-id", "call-1"
        );

        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    void toolFailureReturnsOne() {
        int exitCode = commandLine().execute(
            "--name", "read_file",
            "--args", "{\"path\":\"missing.txt\"}",
            "--dir", tempDir.toString(),
            "--call-id", "call-1"
        );

        assertThat(exitCode).isEqualTo(1);
        assertThat(out.toString()).contains("error: true");
        assertThat(out.toString()).contains("cannot be accessed");
    }

    @Test
    void outputContainsAllFields() throws IOException {
        Files.writeString(tempDir.resolve("notes.txt"), "content");

        commandLine().execute(
            "--name", "read_file",
            "--args", "{\"path\":\"notes.txt\"}",
            "--dir", tempDir.toString(),
            "--call-id", "call-1"
        );

        String output = out.toString();
        assertThat(output).contains("toolCallId:");
        assertThat(output).contains("toolName:");
        assertThat(output).contains("error:");
        assertThat(output).contains("output:");
    }
}

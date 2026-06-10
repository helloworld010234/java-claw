package com.tinyclaw.adapters.cli;

import com.tinyclaw.adapters.persistence.JdbcMessageRepository;
import com.tinyclaw.adapters.persistence.JdbcRunRepository;
import com.tinyclaw.adapters.persistence.JdbcToolExecutionRepository;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.session.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ShowRunCommandTest {

    @Autowired
    private JdbcRunRepository runRepository;

    @Autowired
    private JdbcMessageRepository messageRepository;

    @Autowired
    private JdbcToolExecutionRepository toolExecutionRepository;

    private ShowRunCommand command;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
        command = new ShowRunCommand(runRepository, messageRepository, toolExecutionRepository);
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
    void showExistingRunOutputsSummary() {
        Session session = Session.create("show-sess", "/tmp", Instant.now());
        AgentRun run = AgentRun.start("show-run", "show-sess", 3, Instant.now());
        runRepository.saveSession(session);
        runRepository.saveRunStarted(run, "agent", "test prompt");
        runRepository.saveRunCompleted(run.complete(Instant.now()));

        int exitCode = commandLine().execute("--run-id", "show-run");
        restoreStreams();

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("runId: show-run");
        assertThat(output).contains("sessionId: show-sess");
        assertThat(output).contains("mode: agent");
        assertThat(output).contains("status: completed");
        assertThat(output).contains("messages:");
        assertThat(output).contains("toolExecutions:");
    }

    @Test
    void showMissingRunReturnsTwo() {
        int exitCode = commandLine().execute("--run-id", "missing-run-id");
        restoreStreams();

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("Run not found");
    }
}

package com.tinyclaw.adapters.cli;

import com.tinyclaw.adapters.persistence.JdbcMessageRepository;
import com.tinyclaw.adapters.persistence.JdbcRunRepository;
import com.tinyclaw.adapters.persistence.JdbcToolExecutionRepository;
import com.tinyclaw.adapters.persistence.JdbcUsageRepository;
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

    @Autowired
    private JdbcUsageRepository usageRepository;

    private ShowRunCommand command;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
        command = new ShowRunCommand(runRepository, messageRepository, toolExecutionRepository, usageRepository);
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
        runRepository.saveRunCompleted(run.id(), 2, Instant.now());

        int exitCode = commandLine().execute("--run-id", "show-run");
        restoreStreams();

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("runId: show-run");
        assertThat(output).contains("sessionId: show-sess");
        assertThat(output).contains("mode: agent");
        assertThat(output).contains("status: success");
        assertThat(output).contains("turns: 2");
        assertThat(output).contains("messages:");
        assertThat(output).contains("toolExecutions:");
    }

    @Test
    void showFailedRunOutputsStatusFailed() {
        Session session = Session.create("show-fail-sess", "/tmp", Instant.now());
        AgentRun run = AgentRun.start("show-fail-run", "show-fail-sess", 5, Instant.now());
        runRepository.saveSession(session);
        runRepository.saveRunStarted(run, "agent", "fail prompt");
        runRepository.saveRunFailed(run.id(), 2, "something broke", Instant.now());

        int exitCode = commandLine().execute("--run-id", "show-fail-run");
        restoreStreams();

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("status: failed");
        assertThat(output).contains("turns: 2");
    }

    @Test
    void showMissingRunReturnsTwo() {
        int exitCode = commandLine().execute("--run-id", "missing-run-id");
        restoreStreams();

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("Run not found");
    }

    @Test
    void showRunWithoutUsageOutputsNone() {
        Session session = Session.create("show-no-usage-sess", "/tmp", Instant.now());
        AgentRun run = AgentRun.start("show-no-usage-run", "show-no-usage-sess", 3, Instant.now());
        runRepository.saveSession(session);
        runRepository.saveRunStarted(run, "agent", "test prompt");
        runRepository.saveRunCompleted(run.id(), 1, Instant.now());

        int exitCode = commandLine().execute("--run-id", "show-no-usage-run");
        restoreStreams();

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("usage: none");
    }

    @Test
    void showRunWithUsageOutputsSummary() {
        Session session = Session.create("show-usage-sess", "/tmp", Instant.now());
        AgentRun run = AgentRun.start("show-usage-run", "show-usage-sess", 3, Instant.now());
        runRepository.saveSession(session);
        runRepository.saveRunStarted(run, "agent", "test prompt");
        runRepository.saveRunCompleted(run.id(), 2, Instant.now());

        usageRepository.save(new com.tinyclaw.ports.persistence.UsageRecord(
            "show-usage-run", "show-usage-sess", "fake", 100, 50, null, true, Instant.now()
        ));

        int exitCode = commandLine().execute("--run-id", "show-usage-run");
        restoreStreams();

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("usage: 100 prompt / 50 completion tokens");
    }

    @Test
    void showRunDetailWithUsageOutputsRecords() {
        Session session = Session.create("show-detail-sess", "/tmp", Instant.now());
        AgentRun run = AgentRun.start("show-detail-run", "show-detail-sess", 3, Instant.now());
        runRepository.saveSession(session);
        runRepository.saveRunStarted(run, "agent", "test prompt");
        runRepository.saveRunCompleted(run.id(), 2, Instant.now());

        usageRepository.save(new com.tinyclaw.ports.persistence.UsageRecord(
            "show-detail-run", "show-detail-sess", "fake", 200, 80, null, true, Instant.now()
        ));

        int exitCode = commandLine().execute("--run-id", "show-detail-run", "--detail");
        restoreStreams();

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("usage:");
        assertThat(output).contains("200 prompt / 80 completion tokens");
        assertThat(output).contains("total: 200 prompt / 80 completion tokens");
    }
}

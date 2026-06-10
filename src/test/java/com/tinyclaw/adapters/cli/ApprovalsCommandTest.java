package com.tinyclaw.adapters.cli;

import com.tinyclaw.adapters.persistence.JdbcApprovalRepository;
import com.tinyclaw.adapters.persistence.JdbcRunRepository;
import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
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

/**
 * CLI approvals 命令集成测试。
 */
@SpringBootTest
@ActiveProfiles("test")
class ApprovalsCommandTest {

    @Autowired
    private JdbcApprovalRepository approvalRepository;

    @Autowired
    private JdbcRunRepository runRepository;

    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
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

    private void seedRunAndApproval(String runId, String approvalId, ApprovalStatus status) {
        Session session = Session.create("sess-" + runId, "/tmp", Instant.now());
        runRepository.saveSession(session);
        AgentRun run = AgentRun.start(runId, session.id(), 5, Instant.now());
        runRepository.saveRunStarted(run, "plan", "test");

        ApprovalRequest req = ApprovalRequest.pending(
            approvalId, runId, session.id(), "tc-1", "shell_command", "{\"command\":\"echo hi\"}", Instant.now()
        );
        approvalRepository.save(req);

        if (status == ApprovalStatus.APPROVED) {
            approvalRepository.update(req.approve("ok", Instant.now()));
        } else if (status == ApprovalStatus.REJECTED) {
            approvalRepository.update(req.reject("no", Instant.now()));
        }
    }

    @Test
    void listOutputsPendingApproval() {
        seedRunAndApproval("run-list", "apr-list", ApprovalStatus.PENDING);

        ListApprovalsCommand cmd = new ListApprovalsCommand(approvalRepository);
        int exitCode = new CommandLine(cmd).execute("--status", "PENDING");
        restoreStreams();

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("apr-list").contains("shell_command").contains("PENDING");
    }

    @Test
    void showOutputsFullFields() {
        seedRunAndApproval("run-show", "apr-show", ApprovalStatus.PENDING);

        ShowApprovalCommand cmd = new ShowApprovalCommand(approvalRepository);
        int exitCode = new CommandLine(cmd).execute("--approval-id", "apr-show");
        restoreStreams();

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("approvalId: apr-show");
        assertThat(output).contains("runId: run-show");
        assertThat(output).contains("sessionId: sess-run-show");
        assertThat(output).contains("toolCallId: tc-1");
        assertThat(output).contains("toolName: shell_command");
        assertThat(output).contains("status: PENDING");
        assertThat(output).contains("argumentsPreview:");
        assertThat(output).contains("requestedAt:");
    }

    @Test
    void approvePendingSuccess() {
        seedRunAndApproval("run-approve", "apr-approve", ApprovalStatus.PENDING);

        ApproveCommand cmd = new ApproveCommand(approvalRepository, java.time.Clock.systemUTC());
        int exitCode = new CommandLine(cmd).execute(
            "--approval-id", "apr-approve",
            "--reason", "operator confirmed"
        );
        restoreStreams();

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("status: APPROVED");

        ApprovalRequest updated = approvalRepository.findById("apr-approve").orElseThrow();
        assertThat(updated.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(updated.decisionReason()).isEqualTo("operator confirmed");
    }

    @Test
    void rejectPendingSuccess() {
        seedRunAndApproval("run-reject", "apr-reject", ApprovalStatus.PENDING);

        RejectApprovalCommand cmd = new RejectApprovalCommand(approvalRepository, java.time.Clock.systemUTC());
        int exitCode = new CommandLine(cmd).execute(
            "--approval-id", "apr-reject",
            "--reason", "unsafe command"
        );
        restoreStreams();

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("status: REJECTED");

        ApprovalRequest updated = approvalRepository.findById("apr-reject").orElseThrow();
        assertThat(updated.status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(updated.decisionReason()).isEqualTo("unsafe command");
    }

    @Test
    void approveNonExistentReturnsNonZero() {
        ApproveCommand cmd = new ApproveCommand(approvalRepository, java.time.Clock.systemUTC());
        int exitCode = new CommandLine(cmd).execute(
            "--approval-id", "missing",
            "--reason", "operator confirmed"
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("not found");
    }

    @Test
    void approveAlreadyApprovedReturnsNonZero() {
        seedRunAndApproval("run-double", "apr-double", ApprovalStatus.APPROVED);

        ApproveCommand cmd = new ApproveCommand(approvalRepository, java.time.Clock.systemUTC());
        int exitCode = new CommandLine(cmd).execute(
            "--approval-id", "apr-double",
            "--reason", "again"
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("not pending");
    }

    @Test
    void blankReasonReturnsNonZero() {
        seedRunAndApproval("run-blank", "apr-blank", ApprovalStatus.PENDING);

        ApproveCommand cmd = new ApproveCommand(approvalRepository, java.time.Clock.systemUTC());
        int exitCode = new CommandLine(cmd).execute(
            "--approval-id", "apr-blank",
            "--reason", "   "
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("blank");
    }
}

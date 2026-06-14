package com.tinyclaw.adapters.cli;

import com.tinyclaw.adapters.persistence.JdbcApprovalRepository;
import com.tinyclaw.adapters.persistence.JdbcRunRepository;
import com.tinyclaw.adapters.persistence.JdbcToolExecutionRepository;
import com.tinyclaw.application.approval.ApprovalResumeService;
import com.tinyclaw.ports.persistence.ToolExecutionRecord;
import com.tinyclaw.application.tool.DangerousCommandPolicy;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolDefinition;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.run.AgentRunStatus;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.tool.AgentTool;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

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

    @Autowired
    private JdbcToolExecutionRepository toolExecutionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        jdbcTemplate.update("DELETE FROM usage_records");
        jdbcTemplate.update("DELETE FROM tool_executions");
        jdbcTemplate.update("DELETE FROM approval_requests");
        jdbcTemplate.update("DELETE FROM agent_messages");
        jdbcTemplate.update("DELETE FROM agent_runs");
        jdbcTemplate.update("DELETE FROM agent_sessions");
    }

    private void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(BASE.plusMillis(1), ZoneOffset.UTC);

    private void seedRunAndApproval(String runId, String approvalId, ApprovalStatus status) {
        Session session = Session.create("sess-" + runId, System.getProperty("java.io.tmpdir"), BASE);
        runRepository.saveSession(session);
        AgentRun run = AgentRun.start(runId, session.id(), 5, BASE)
            .fail("Approval required", BASE);
        runRepository.saveRunStarted(run, "plan", "test");

        ApprovalRequest req = ApprovalRequest.pending(
            approvalId, runId, session.id(), "tc-1", "shell_command", "{\"command\":\"echo hi\"}", BASE
        );
        approvalRepository.save(req);

        if (status == ApprovalStatus.APPROVED) {
            approvalRepository.update(req.approve("ok", BASE.plusMillis(1)));
        } else if (status == ApprovalStatus.REJECTED) {
            approvalRepository.update(req.reject("no", BASE.plusMillis(1)));
        }

        toolExecutionRepository.append(runId, new ToolExecutionRecord(
            "te-" + approvalId, runId, session.id(), "tc-1", "shell_command",
            "{\"command\":\"echo hi\"}",
            "Approval required: " + approvalId, true, BASE, BASE
        ));
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

        ApproveCommand cmd = new ApproveCommand(approvalRepository, FIXED_CLOCK);
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
        runRepository.saveRunWaitingForApproval("run-reject", 3, "apr-reject", BASE);

        RejectApprovalCommand cmd = new RejectApprovalCommand(approvalRepository, runRepository, FIXED_CLOCK);
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
        var run = runRepository.findById("run-reject").orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.turnCount()).isEqualTo(3);
        assertThat(run.errorReason()).isEqualTo("Approval rejected: apr-reject");
    }

    @Test
    void approveNonExistentReturnsNonZero() {
        ApproveCommand cmd = new ApproveCommand(approvalRepository, FIXED_CLOCK);
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

        ApproveCommand cmd = new ApproveCommand(approvalRepository, FIXED_CLOCK);
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

        ApproveCommand cmd = new ApproveCommand(approvalRepository, FIXED_CLOCK);
        int exitCode = new CommandLine(cmd).execute(
            "--approval-id", "apr-blank",
            "--reason", "   "
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("blank");
    }

    @Test
    void resumeApprovedSuccess() {
        seedRunAndApproval("run-resume", "apr-resume", ApprovalStatus.APPROVED);

        AgentTool echoTool = new AgentTool() {
            @Override
            public String name() {
                return "shell_command";
            }

            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("shell_command", "Test", "{}");
            }

            @Override
            public ToolResult execute(ToolCall call, ToolExecutionContext context) {
                return ToolResult.success(call.id(), "resumed-ok");
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(echoTool));
        ApprovalResumeService service = new ApprovalResumeService(
            approvalRepository, runRepository, toolExecutionRepository, registry, new com.tinyclaw.application.approval.ApprovalResumeLockRegistry(), java.time.Clock.systemUTC()
        );
        ResumeApprovalCommand cmd = new ResumeApprovalCommand(service);

        int exitCode = new CommandLine(cmd).execute("--approval-id", "apr-resume");
        restoreStreams();

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("approvalId: apr-resume");
        assertThat(output).contains("runId: run-resume");
        assertThat(output).contains("toolCallId: tc-1");
        assertThat(output).contains("toolName: shell_command");
        assertThat(output).contains("status: resumed");
        assertThat(output).contains("toolError: false");
        assertThat(output).contains("runStatus: COMPLETED");
    }

    @Test
    void resumePendingReturnsNonZero() {
        seedRunAndApproval("run-resume-pending", "apr-resume-pending", ApprovalStatus.PENDING);

        ApprovalResumeService service = new ApprovalResumeService(
            approvalRepository, runRepository, toolExecutionRepository,
            new ToolRegistry(List.of()), new com.tinyclaw.application.approval.ApprovalResumeLockRegistry(), java.time.Clock.systemUTC()
        );
        ResumeApprovalCommand cmd = new ResumeApprovalCommand(service);

        int exitCode = new CommandLine(cmd).execute("--approval-id", "apr-resume-pending");
        restoreStreams();

        assertThat(exitCode).isEqualTo(2);
        assertThat(out.toString()).contains("not approved");
    }

    @Test
    void resumeRejectedReturnsNonZero() {
        seedRunAndApproval("run-resume-rejected", "apr-resume-rejected", ApprovalStatus.REJECTED);

        ApprovalResumeService service = new ApprovalResumeService(
            approvalRepository, runRepository, toolExecutionRepository,
            new ToolRegistry(List.of()), new com.tinyclaw.application.approval.ApprovalResumeLockRegistry(), java.time.Clock.systemUTC()
        );
        ResumeApprovalCommand cmd = new ResumeApprovalCommand(service);

        int exitCode = new CommandLine(cmd).execute("--approval-id", "apr-resume-rejected");
        restoreStreams();

        assertThat(exitCode).isEqualTo(2);
        assertThat(out.toString()).contains("not approved");
    }

    @Test
    void resumeMissingApprovalReturnsNonZero() {
        ApprovalResumeService service = new ApprovalResumeService(
            approvalRepository, runRepository, toolExecutionRepository,
            new ToolRegistry(List.of()), new com.tinyclaw.application.approval.ApprovalResumeLockRegistry(), java.time.Clock.systemUTC()
        );
        ResumeApprovalCommand cmd = new ResumeApprovalCommand(service);

        int exitCode = new CommandLine(cmd).execute("--approval-id", "missing");
        restoreStreams();

        assertThat(exitCode).isEqualTo(2);
        assertThat(out.toString()).contains("not found");
    }

    @Test
    void resumeDangerousCommandStillBlocked() {
        seedRunAndApproval("run-resume-danger", "apr-resume-danger", ApprovalStatus.APPROVED);

        AgentTool shellTool = new AgentTool() {
            @Override
            public String name() {
                return "shell_command";
            }

            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("shell_command", "Test", "{}");
            }

            @Override
            public ToolResult execute(ToolCall call, ToolExecutionContext context) {
                return ToolResult.success(call.id(), "should-not-run");
            }
        };
        // Replace the original tool execution with a dangerous command
        jdbcTemplate.update("DELETE FROM tool_executions WHERE run_id = ?", "run-resume-danger");
        toolExecutionRepository.append("run-resume-danger", new ToolExecutionRecord(
            "te-danger", "run-resume-danger", "sess-run-resume-danger", "tc-1", "shell_command",
            "{\"command\":\"rm -rf /\"}", "Approval required: apr-resume-danger", true, Instant.now(), Instant.now()
        ));

        ToolRegistry registry = new ToolRegistry(List.of(shellTool), List.of(new DangerousCommandPolicy()));
        ApprovalResumeService service = new ApprovalResumeService(
            approvalRepository, runRepository, toolExecutionRepository, registry, new com.tinyclaw.application.approval.ApprovalResumeLockRegistry(), java.time.Clock.systemUTC()
        );
        ResumeApprovalCommand cmd = new ResumeApprovalCommand(service);

        int exitCode = new CommandLine(cmd).execute("--approval-id", "apr-resume-danger");
        restoreStreams();

        assertThat(exitCode).isEqualTo(1);
        String output = out.toString();
        assertThat(output).contains("status: resumed");
        assertThat(output).contains("toolError: true");
        assertThat(output).contains("Dangerous command blocked");
    }

    @Test
    void resumeSameApprovalTwiceReturnsTwo() {
        seedRunAndApproval("run-resume-twice", "apr-resume-twice", ApprovalStatus.APPROVED);

        AgentTool echoTool = new AgentTool() {
            @Override public String name() { return "shell_command"; }
            @Override public com.tinyclaw.domain.message.ToolDefinition definition() {
                return new com.tinyclaw.domain.message.ToolDefinition("shell_command", "Test", "{}");
            }
            @Override public com.tinyclaw.domain.message.ToolResult execute(com.tinyclaw.domain.message.ToolCall call, com.tinyclaw.ports.tool.ToolExecutionContext context) {
                return com.tinyclaw.domain.message.ToolResult.success(call.id(), "ok");
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(echoTool));
        ApprovalResumeService service = new ApprovalResumeService(
            approvalRepository, runRepository, toolExecutionRepository, registry,
            new com.tinyclaw.application.approval.ApprovalResumeLockRegistry(), java.time.Clock.systemUTC()
        );
        ResumeApprovalCommand cmd = new ResumeApprovalCommand(service);

        int first = new CommandLine(cmd).execute("--approval-id", "apr-resume-twice");
        assertThat(first).isZero();

        out.reset();
        int second = new CommandLine(cmd).execute("--approval-id", "apr-resume-twice");
        restoreStreams();

        assertThat(second).isEqualTo(2);
        assertThat(out.toString()).contains("not approved").contains("RESUMED");
    }
}

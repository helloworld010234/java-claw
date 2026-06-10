package com.tinyclaw.adapters.cli;

import com.tinyclaw.adapters.persistence.JdbcApprovalRepository;
import com.tinyclaw.adapters.persistence.JdbcRunRepository;
import com.tinyclaw.adapters.persistence.JdbcToolExecutionRepository;
import com.tinyclaw.application.approval.ApprovalResumeService;
import com.tinyclaw.application.persistence.ToolExecutionRecord;
import com.tinyclaw.application.tool.DangerousCommandPolicy;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolDefinition;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.domain.run.AgentRun;
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
import java.time.Instant;
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
        jdbcTemplate.update("DELETE FROM tool_executions");
        jdbcTemplate.update("DELETE FROM approval_requests");
        jdbcTemplate.update("DELETE FROM agent_runs");
        jdbcTemplate.update("DELETE FROM agent_sessions");
    }

    private void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private void seedRunAndApproval(String runId, String approvalId, ApprovalStatus status) {
        Session session = Session.create("sess-" + runId, System.getProperty("java.io.tmpdir"), Instant.now());
        runRepository.saveSession(session);
        AgentRun run = AgentRun.start(runId, session.id(), 5, Instant.now())
            .fail("Approval required", Instant.now());
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

        toolExecutionRepository.append(runId, new ToolExecutionRecord(
            "te-" + approvalId, runId, session.id(), "tc-1", "shell_command",
            "{\"command\":\"echo hi\"}",
            "Approval required: " + approvalId, true, Instant.now(), Instant.now()
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
            approvalRepository, runRepository, toolExecutionRepository, registry, java.time.Clock.systemUTC()
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
            new ToolRegistry(List.of()), java.time.Clock.systemUTC()
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
            new ToolRegistry(List.of()), java.time.Clock.systemUTC()
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
            new ToolRegistry(List.of()), java.time.Clock.systemUTC()
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
            approvalRepository, runRepository, toolExecutionRepository, registry, java.time.Clock.systemUTC()
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
}

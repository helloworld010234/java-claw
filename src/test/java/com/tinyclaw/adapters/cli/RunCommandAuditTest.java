package com.tinyclaw.adapters.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.llm.fake.FakeLlmGateway;
import com.tinyclaw.adapters.persistence.JdbcApprovalRepository;
import com.tinyclaw.adapters.persistence.JdbcMessageRepository;
import com.tinyclaw.adapters.persistence.JdbcRunRepository;
import com.tinyclaw.adapters.persistence.JdbcToolExecutionRepository;
import com.tinyclaw.adapters.reporter.NoOpReporter;
import com.tinyclaw.adapters.session.InMemorySessionService;
import com.tinyclaw.adapters.tools.command.ShellCommandTool;
import com.tinyclaw.adapters.tools.filesystem.EditFileTool;
import com.tinyclaw.adapters.tools.filesystem.ReadFileTool;
import com.tinyclaw.adapters.tools.filesystem.WriteFileTool;
import com.tinyclaw.application.approval.ApprovalGatePolicy;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.PromptComposer;
import com.tinyclaw.application.persistence.AgentMessageDto;
import com.tinyclaw.application.persistence.AgentRunSummary;
import com.tinyclaw.application.persistence.ToolExecutionRecord;
import com.tinyclaw.application.run.ScriptedRunExecutor;
import com.tinyclaw.application.tool.AllowAllPolicy;
import com.tinyclaw.application.tool.DangerousCommandPolicy;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.message.Role;
import com.tinyclaw.domain.run.AgentRunStatus;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RunCommand 持久化审计集成测试。
 *
 * <p>验证 fake 和 plan-file 模式的执行过程正确持久化到 H2 数据库。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class RunCommandAuditTest {

    @TempDir
    Path tempDir;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JdbcRunRepository runRepository;

    @Autowired
    private JdbcMessageRepository messageRepository;

    @Autowired
    private JdbcToolExecutionRepository toolExecutionRepository;

    @Autowired
    private JdbcApprovalRepository approvalRepository;

    private RunCommand command;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
        ToolRegistry registry = new ToolRegistry(
            List.of(
                new ReadFileTool(),
                new WriteFileTool(),
                new EditFileTool(),
                new ShellCommandTool()
            ),
            List.of(new AllowAllPolicy(), new DangerousCommandPolicy())
        );
        LlmGateway dummyLlm = request -> new LlmResponse("", List.of(), null);
        InMemorySessionService sessionService = new InMemorySessionService();
        AgentEngine agentEngine = new AgentEngine(
            dummyLlm, registry, new PromptComposer(), new NoOpReporter(), sessionService
        );
        command = new RunCommand(
            new ScriptedRunExecutor(registry, new ObjectMapper()),
            agentEngine,
            new ObjectMapper(),
            sessionService,
            runRepository,
            messageRepository,
            toolExecutionRepository
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

    private String extractRunId(String output) {
        for (String line : output.split("\n")) {
            if (line.startsWith("runId:")) {
                return line.substring("runId:".length()).trim();
            }
        }
        return null;
    }

    // --- fake mode audit tests ---

    @Test
    void engineFakeTextReplyPersistsUserAndAssistantMessages() {
        int exitCode = commandLine().execute(
            "--prompt", "hello",
            "--dir", tempDir.toString(),
            "--session", "audit-fake-text",
            "--engine", "fake"
        );
        restoreStreams();

        assertThat(exitCode).isZero();
        String output = out.toString();
        String runId = extractRunId(output);
        assertThat(runId).isNotNull();

        AgentRunSummary run = runRepository.findById(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(run.mode()).isEqualTo("agent");
        assertThat(run.prompt()).isEqualTo("hello");

        List<AgentMessageDto> messages = messageRepository.findByRunId(runId);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo(Role.USER);
        assertThat(messages.get(0).content()).isEqualTo("hello");
        assertThat(messages.get(1).role()).isEqualTo(Role.ASSISTANT);
    }

    @Test
    void engineFakeToolSuccessPersistsFourMessages() {
        int exitCode = commandLine().execute(
            "--prompt", "write something",
            "--dir", tempDir.toString(),
            "--session", "audit-fake-tool",
            "--engine", "fake"
        );
        restoreStreams();

        assertThat(exitCode).isZero();
        String runId = extractRunId(out.toString());
        assertThat(runId).isNotNull();

        AgentRunSummary run = runRepository.findById(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);

        List<AgentMessageDto> messages = messageRepository.findByRunId(runId);
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).role()).isEqualTo(Role.USER);
        assertThat(messages.get(1).role()).isEqualTo(Role.ASSISTANT);
        assertThat(messages.get(2).role()).isEqualTo(Role.USER);
        assertThat(messages.get(2).toolCallId()).isNotNull();
        assertThat(messages.get(3).role()).isEqualTo(Role.ASSISTANT);
    }

    @Test
    void engineFakeToolFailurePersistsFourMessages() {
        int exitCode = commandLine().execute(
            "--prompt", "read missing",
            "--dir", tempDir.toString(),
            "--session", "audit-fake-fail",
            "--engine", "fake"
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(1);
        String runId = extractRunId(out.toString());
        assertThat(runId).isNotNull();

        AgentRunSummary run = runRepository.findById(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.errorReason()).isNotBlank();

        List<AgentMessageDto> messages = messageRepository.findByRunId(runId);
        assertThat(messages).hasSize(4);
    }

    @Test
    void engineFakeLlmFailureMarksRunAsFailed() {
        int exitCode = commandLine().execute(
            "--prompt", "fail now",
            "--dir", tempDir.toString(),
            "--session", "audit-fake-llm-fail",
            "--engine", "fake"
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(1);
        String runId = extractRunId(out.toString());
        assertThat(runId).isNotNull();

        AgentRunSummary run = runRepository.findById(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
    }

    @Test
    void engineFakePersistsActualTurnCount() {
        // text success -> 1 turn
        commandLine().execute(
            "--prompt", "hello",
            "--dir", tempDir.toString(),
            "--session", "audit-turn-text",
            "--engine", "fake"
        );
        restoreStreams();
        String runId1 = extractRunId(out.toString());
        AgentRunSummary run1 = runRepository.findById(runId1).orElseThrow();
        assertThat(run1.turnCount()).isEqualTo(1);

        // tool success -> 2 turns
        setUp();
        commandLine().execute(
            "--prompt", "write something",
            "--dir", tempDir.toString(),
            "--session", "audit-turn-tool",
            "--engine", "fake"
        );
        restoreStreams();
        String runId2 = extractRunId(out.toString());
        AgentRunSummary run2 = runRepository.findById(runId2).orElseThrow();
        assertThat(run2.turnCount()).isEqualTo(2);

        // tool failure -> 2 turns
        setUp();
        commandLine().execute(
            "--prompt", "read missing",
            "--dir", tempDir.toString(),
            "--session", "audit-turn-fail",
            "--engine", "fake"
        );
        restoreStreams();
        String runId3 = extractRunId(out.toString());
        AgentRunSummary run3 = runRepository.findById(runId3).orElseThrow();
        assertThat(run3.turnCount()).isEqualTo(2);
    }

    @Test
    void sameSessionCanRunTwice() {
        String session = "audit-same-session";

        int exitCode1 = commandLine().execute(
            "--prompt", "hello one",
            "--dir", tempDir.toString(),
            "--session", session,
            "--engine", "fake"
        );
        restoreStreams();
        String runId1 = extractRunId(out.toString());
        assertThat(exitCode1).isZero();

        setUp();
        int exitCode2 = commandLine().execute(
            "--prompt", "hello two",
            "--dir", tempDir.toString(),
            "--session", session,
            "--engine", "fake"
        );
        restoreStreams();
        String runId2 = extractRunId(out.toString());
        assertThat(exitCode2).isZero();

        assertThat(runId1).isNotEqualTo(runId2);
        assertThat(runRepository.findById(runId1)).isPresent();
        assertThat(runRepository.findById(runId2)).isPresent();
    }

    @Test
    void sameSessionSamePromptTwicePersistsBothRuns() {
        String session = "audit-same-prompt";

        int exitCode1 = commandLine().execute(
            "--prompt", "hello",
            "--dir", tempDir.toString(),
            "--session", session,
            "--engine", "fake"
        );
        restoreStreams();
        String runId1 = extractRunId(out.toString());
        assertThat(exitCode1).isZero();

        List<AgentMessageDto> messages1 = messageRepository.findByRunId(runId1);
        assertThat(messages1).hasSize(2);

        setUp();
        int exitCode2 = commandLine().execute(
            "--prompt", "hello",
            "--dir", tempDir.toString(),
            "--session", session,
            "--engine", "fake"
        );
        restoreStreams();
        String runId2 = extractRunId(out.toString());
        assertThat(exitCode2).isZero();

        List<AgentMessageDto> messages2 = messageRepository.findByRunId(runId2);
        assertThat(messages2).hasSize(2);

        assertThat(runId1).isNotEqualTo(runId2);
    }

    @Test
    void engineFakeShellCommandSucceedsAndPersistsMessagesAndToolExecution() {
        int exitCode = commandLine().execute(
            "--prompt", "run command",
            "--dir", tempDir.toString(),
            "--session", "audit-fake-shell",
            "--engine", "fake"
        );
        restoreStreams();

        assertThat(exitCode).isZero();
        String runId = extractRunId(out.toString());
        assertThat(runId).isNotNull();

        AgentRunSummary run = runRepository.findById(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(run.turnCount()).isEqualTo(2);

        List<AgentMessageDto> messages = messageRepository.findByRunId(runId);
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).role()).isEqualTo(Role.USER);
        assertThat(messages.get(1).role()).isEqualTo(Role.ASSISTANT);
        assertThat(messages.get(2).role()).isEqualTo(Role.USER);
        assertThat(messages.get(2).toolCallId()).isNotNull();
        assertThat(messages.get(3).role()).isEqualTo(Role.ASSISTANT);

        List<ToolExecutionRecord> executions = toolExecutionRepository.findByRunId(runId);
        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).toolName()).isEqualTo("shell_command");
        assertThat(executions.get(0).isError()).isFalse();
        assertThat(executions.get(0).output()).containsIgnoringCase("hello-from-shell");
    }

    // --- plan-file mode audit tests ---

    @Test
    void planFileSuccessPersistsToolExecutions() throws IOException {
        String plan = """
            {
              "stopOnError": true,
              "steps": [
                {"id": "write-plan", "tool": "write_file", "args": {"path": "plan.txt", "content": "from-plan", "overwrite": true}},
                {"id": "read-plan", "tool": "read_file", "args": {"path": "plan.txt"}}
              ]
            }
            """;
        Path planFile = tempDir.resolve("plan.json");
        Files.writeString(planFile, plan);

        int exitCode = commandLine().execute(
            "--prompt", "plan persist",
            "--dir", tempDir.toString(),
            "--session", "audit-plan-success",
            "--plan-file", planFile.toString()
        );
        restoreStreams();

        assertThat(exitCode).isZero();
        String runId = extractRunId(out.toString());
        assertThat(runId).isNotNull();

        AgentRunSummary run = runRepository.findById(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(run.mode()).isEqualTo("plan");

        List<ToolExecutionRecord> executions = toolExecutionRepository.findByRunId(runId);
        assertThat(executions).hasSize(2);
        assertThat(executions.get(0).stepId()).isEqualTo("write-plan");
        assertThat(executions.get(1).stepId()).isEqualTo("read-plan");
        assertThat(executions.stream().allMatch(e -> !e.isError())).isTrue();
    }

    @Test
    void planFileFailureWithStopOnErrorPersistsOnlyExecutedSteps() throws IOException {
        String plan = """
            {
              "stopOnError": true,
              "steps": [
                {"id": "fail-step", "tool": "read_file", "args": {"path": "missing.txt"}},
                {"id": "never-run", "tool": "write_file", "args": {"path": "never.txt", "content": "x", "overwrite": true}}
              ]
            }
            """;
        Path planFile = tempDir.resolve("fail-plan.json");
        Files.writeString(planFile, plan);

        int exitCode = commandLine().execute(
            "--prompt", "plan fail",
            "--dir", tempDir.toString(),
            "--session", "audit-plan-fail",
            "--plan-file", planFile.toString()
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(1);
        String runId = extractRunId(out.toString());
        assertThat(runId).isNotNull();

        AgentRunSummary run = runRepository.findById(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);

        List<ToolExecutionRecord> executions = toolExecutionRepository.findByRunId(runId);
        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).stepId()).isEqualTo("fail-step");
        assertThat(executions.get(0).isError()).isTrue();
    }

    @Test
    void planFileShellCommandPersistsExecution() throws IOException {
        String command = System.getProperty("os.name").toLowerCase().contains("windows")
            ? "Write-Output 'plan-shell'"
            : "echo plan-shell";
        String plan = """
            {
              "stopOnError": true,
              "steps": [
                {"id": "shell-step", "tool": "shell_command", "args": {"command": "%s"}}
              ]
            }
            """.formatted(command);
        Path planFile = tempDir.resolve("shell-plan.json");
        Files.writeString(planFile, plan);

        int exitCode = commandLine().execute(
            "--prompt", "plan shell",
            "--dir", tempDir.toString(),
            "--session", "audit-plan-shell",
            "--plan-file", planFile.toString()
        );
        restoreStreams();

        assertThat(exitCode).isZero();
        String runId = extractRunId(out.toString());
        assertThat(runId).isNotNull();

        List<ToolExecutionRecord> executions = toolExecutionRepository.findByRunId(runId);
        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).stepId()).isEqualTo("shell-step");
        assertThat(executions.get(0).toolName()).isEqualTo("shell_command");
        assertThat(executions.get(0).isError()).isFalse();
    }

    @Test
    void planFileDangerousShellCommandIsBlockedByPolicy() throws IOException {
        String plan = """
            {
              "stopOnError": true,
              "steps": [
                {"id": "danger-step", "tool": "shell_command", "args": {"command": "rm -rf /"}}
              ]
            }
            """;
        Path planFile = tempDir.resolve("danger-plan.json");
        Files.writeString(planFile, plan);

        int exitCode = commandLine().execute(
            "--prompt", "plan danger",
            "--dir", tempDir.toString(),
            "--session", "audit-plan-danger",
            "--plan-file", planFile.toString()
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(1);
        String runId = extractRunId(out.toString());
        assertThat(runId).isNotNull();

        AgentRunSummary run = runRepository.findById(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);

        List<ToolExecutionRecord> executions = toolExecutionRepository.findByRunId(runId);
        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).stepId()).isEqualTo("danger-step");
        assertThat(executions.get(0).isError()).isTrue();
        assertThat(executions.get(0).output()).contains("Dangerous command blocked");
    }

    // --- long session / runId boundary tests ---

    @Test
    void engineFakeWithMaxLengthSessionIdSucceeds() {
        String longSession = "a".repeat(36);

        int exitCode = commandLine().execute(
            "--prompt", "hello",
            "--dir", tempDir.toString(),
            "--session", longSession,
            "--engine", "fake"
        );
        restoreStreams();

        assertThat(exitCode).isZero();
        String runId = extractRunId(out.toString());
        assertThat(runId).isNotNull();
        assertThat(runId.length()).isLessThanOrEqualTo(36);
        assertThat(runId).doesNotContain(longSession);

        AgentRunSummary run = runRepository.findById(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(run.sessionId()).isEqualTo(longSession);

        List<AgentMessageDto> messages = messageRepository.findByRunId(runId);
        assertThat(messages).hasSize(2);
    }

    @Test
    void planFileWithMaxLengthSessionIdSucceeds() throws IOException {
        String longSession = "p".repeat(36);
        String plan = """
            {
              "stopOnError": true,
              "steps": [
                {"id": "read-step", "tool": "read_file", "args": {"path": "notes.txt"}}
              ]
            }
            """;
        Path planFile = tempDir.resolve("long-session-plan.json");
        Files.writeString(planFile, plan);
        Files.writeString(tempDir.resolve("notes.txt"), "data");

        int exitCode = commandLine().execute(
            "--prompt", "plan long session",
            "--dir", tempDir.toString(),
            "--session", longSession,
            "--plan-file", planFile.toString()
        );
        restoreStreams();

        assertThat(exitCode).isZero();
        String runId = extractRunId(out.toString());
        assertThat(runId).isNotNull();
        assertThat(runId.length()).isLessThanOrEqualTo(36);
        assertThat(runId).doesNotContain(longSession);

        AgentRunSummary run = runRepository.findById(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(run.sessionId()).isEqualTo(longSession);
    }

    @Test
    void sameMaxLengthSessionCanRunTwiceWithDifferentRunIds() {
        String longSession = "s".repeat(36);

        int exitCode1 = commandLine().execute(
            "--prompt", "hello one",
            "--dir", tempDir.toString(),
            "--session", longSession,
            "--engine", "fake"
        );
        restoreStreams();
        String runId1 = extractRunId(out.toString());
        assertThat(exitCode1).isZero();

        setUp();
        int exitCode2 = commandLine().execute(
            "--prompt", "hello two",
            "--dir", tempDir.toString(),
            "--session", longSession,
            "--engine", "fake"
        );
        restoreStreams();
        String runId2 = extractRunId(out.toString());
        assertThat(exitCode2).isZero();

        assertThat(runId1).isNotEqualTo(runId2);
        assertThat(runId1.length()).isLessThanOrEqualTo(36);
        assertThat(runId2.length()).isLessThanOrEqualTo(36);

        AgentRunSummary run1 = runRepository.findById(runId1).orElseThrow();
        AgentRunSummary run2 = runRepository.findById(runId2).orElseThrow();
        assertThat(run1.sessionId()).isEqualTo(longSession);
        assertThat(run2.sessionId()).isEqualTo(longSession);
    }

    @Test
    void engineFakeWithTooLongSessionIdReturnsTwo() {
        String tooLong = "x".repeat(37);
        int exitCode = commandLine().execute(
            "--prompt", "hello",
            "--dir", tempDir.toString(),
            "--session", tooLong,
            "--engine", "fake"
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("Session").contains("36");
    }

    @Test
    void planFileWithApprovalEnabledBlocksShellCommandAndCreatesPendingApproval() throws IOException {
        String command = System.getProperty("os.name").toLowerCase().contains("windows")
            ? "Write-Output 'plan-shell'"
            : "echo plan-shell";
        String plan = """
            {
              "stopOnError": true,
              "steps": [
                {"id": "shell-step", "tool": "shell_command", "args": {"command": "%s"}}
              ]
            }
            """.formatted(command);
        Path planFile = tempDir.resolve("approval-plan.json");
        Files.writeString(planFile, plan);

        ToolRegistry registry = new ToolRegistry(
            List.of(
                new ReadFileTool(),
                new WriteFileTool(),
                new EditFileTool(),
                new ShellCommandTool()
            ),
            List.of(
                new AllowAllPolicy(),
                new DangerousCommandPolicy(),
                new ApprovalGatePolicy(approvalRepository, List.of("shell_command"), java.time.Clock.systemUTC())
            )
        );
        InMemorySessionService sessionService = new InMemorySessionService();
        AgentEngine agentEngine = new AgentEngine(
            request -> new LlmResponse("", List.of(), null),
            registry, new PromptComposer(), new NoOpReporter(), sessionService
        );
        RunCommand approvalCommand = new RunCommand(
            new ScriptedRunExecutor(registry, new ObjectMapper()),
            agentEngine,
            new ObjectMapper(),
            sessionService,
            runRepository,
            messageRepository,
            toolExecutionRepository
        );

        int exitCode = new CommandLine(approvalCommand).execute(
            "--prompt", "plan shell",
            "--dir", tempDir.toString(),
            "--session", "audit-plan-approval",
            "--plan-file", planFile.toString()
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(1);
        String runId = extractRunId(out.toString());
        assertThat(runId).isNotNull();

        AgentRunSummary run = runRepository.findById(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);

        List<ToolExecutionRecord> executions = toolExecutionRepository.findByRunId(runId);
        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).stepId()).isEqualTo("shell-step");
        assertThat(executions.get(0).isError()).isTrue();
        assertThat(executions.get(0).output()).contains("Approval required");
        String approvalId = extractApprovalId(executions.get(0).output());
        assertThat(approvalId).isNotBlank();

        var approvals = approvalRepository.findByRunId(runId);
        assertThat(approvals).hasSize(1);
        assertThat(approvals.get(0).status().name()).isEqualTo("PENDING");
        assertThat(approvals.get(0).toolCallId()).isEqualTo("shell-step");
    }

    @Test
    void engineFakeWithApprovalEnabledBlocksShellCommandAndCreatesPendingApproval() {
        ToolRegistry registry = new ToolRegistry(
            List.of(
                new ReadFileTool(),
                new WriteFileTool(),
                new EditFileTool(),
                new ShellCommandTool()
            ),
            List.of(
                new AllowAllPolicy(),
                new DangerousCommandPolicy(),
                new ApprovalGatePolicy(approvalRepository, List.of("shell_command"), java.time.Clock.systemUTC())
            )
        );
        InMemorySessionService sessionService = new InMemorySessionService();
        AgentEngine agentEngine = new AgentEngine(
            request -> new LlmResponse("", List.of(), null),
            registry, new PromptComposer(), new NoOpReporter(), sessionService
        );
        RunCommand approvalCommand = new RunCommand(
            new ScriptedRunExecutor(registry, new ObjectMapper()),
            agentEngine,
            new ObjectMapper(),
            sessionService,
            runRepository,
            messageRepository,
            toolExecutionRepository
        );

        int exitCode = new CommandLine(approvalCommand).execute(
            "--prompt", "run command",
            "--dir", tempDir.toString(),
            "--session", "audit-fake-approval",
            "--engine", "fake"
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(1);
        String runId = extractRunId(out.toString());
        assertThat(runId).isNotNull();

        AgentRunSummary run = runRepository.findById(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);

        List<ToolExecutionRecord> executions = toolExecutionRepository.findByRunId(runId);
        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).isError()).isTrue();
        assertThat(executions.get(0).output()).contains("Approval required");
        String approvalId = extractApprovalId(executions.get(0).output());
        assertThat(approvalId).isNotBlank();

        var approvals = approvalRepository.findByRunId(runId);
        assertThat(approvals).hasSize(1);
        assertThat(approvals.get(0).status().name()).isEqualTo("PENDING");
    }

    private String extractApprovalId(String output) {
        if (output.contains("Approval required:")) {
            return output.substring(output.lastIndexOf(':') + 1).trim();
        }
        return "";
    }

    @Test
    void planFileDangerousCommandWithApprovalGateDoesNotCreateApproval() throws IOException {
        String plan = """
            {
              "stopOnError": true,
              "steps": [
                {"id": "danger-step", "tool": "shell_command", "args": {"command": "rm -rf /"}}
              ]
            }
            """;
        Path planFile = tempDir.resolve("danger-approval-plan.json");
        Files.writeString(planFile, plan);

        ToolRegistry registry = new ToolRegistry(
            List.of(
                new ReadFileTool(),
                new WriteFileTool(),
                new EditFileTool(),
                new ShellCommandTool()
            ),
            List.of(
                new DangerousCommandPolicy(),
                new ApprovalGatePolicy(approvalRepository, List.of("shell_command"), java.time.Clock.systemUTC())
            )
        );
        InMemorySessionService sessionService = new InMemorySessionService();
        AgentEngine agentEngine = new AgentEngine(
            request -> new LlmResponse("", List.of(), null),
            registry, new PromptComposer(), new NoOpReporter(), sessionService
        );
        RunCommand dangerCommand = new RunCommand(
            new ScriptedRunExecutor(registry, new ObjectMapper()),
            agentEngine,
            new ObjectMapper(),
            sessionService,
            runRepository,
            messageRepository,
            toolExecutionRepository
        );

        int exitCode = new CommandLine(dangerCommand).execute(
            "--prompt", "plan danger",
            "--dir", tempDir.toString(),
            "--session", "audit-danger-approval",
            "--plan-file", planFile.toString()
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(1);
        String runId = extractRunId(out.toString());
        assertThat(runId).isNotNull();

        List<ToolExecutionRecord> executions = toolExecutionRepository.findByRunId(runId);
        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).isError()).isTrue();
        assertThat(executions.get(0).output()).contains("Dangerous command blocked");

        var approvals = approvalRepository.findByRunId(runId);
        assertThat(approvals).isEmpty();
    }

    @Test
    void planFileWithTooLongSessionIdReturnsTwo() throws IOException {
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

        String tooLong = "y".repeat(40);
        int exitCode = commandLine().execute(
            "--prompt", "plan danger",
            "--dir", tempDir.toString(),
            "--session", tooLong,
            "--plan-file", planFile.toString()
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("Session").contains("36");
    }
}

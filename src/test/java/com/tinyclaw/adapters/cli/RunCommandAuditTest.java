package com.tinyclaw.adapters.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.llm.fake.FakeLlmGateway;
import com.tinyclaw.adapters.persistence.JdbcMessageRepository;
import com.tinyclaw.adapters.persistence.JdbcRunRepository;
import com.tinyclaw.adapters.persistence.JdbcToolExecutionRepository;
import com.tinyclaw.adapters.reporter.NoOpReporter;
import com.tinyclaw.adapters.session.InMemorySessionService;
import com.tinyclaw.adapters.tools.filesystem.EditFileTool;
import com.tinyclaw.adapters.tools.filesystem.ReadFileTool;
import com.tinyclaw.adapters.tools.filesystem.WriteFileTool;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.PromptComposer;
import com.tinyclaw.application.persistence.AgentMessageDto;
import com.tinyclaw.application.persistence.AgentRunSummary;
import com.tinyclaw.application.persistence.ToolExecutionRecord;
import com.tinyclaw.application.run.ScriptedRunExecutor;
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

        AgentRunSummary run = runRepository.findById("run-audit-fake-text").orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(run.mode()).isEqualTo("agent");
        assertThat(run.prompt()).isEqualTo("hello");

        List<AgentMessageDto> messages = messageRepository.findByRunId("run-audit-fake-text");
        assertThat(messages).hasSizeGreaterThanOrEqualTo(2);
        assertThat(messages.get(0).role()).isEqualTo(Role.USER);
        assertThat(messages.get(0).content()).isEqualTo("hello");
    }

    @Test
    void engineFakeToolSuccessPersistsToolObservation() {
        int exitCode = commandLine().execute(
            "--prompt", "write something",
            "--dir", tempDir.toString(),
            "--session", "audit-fake-tool",
            "--engine", "fake"
        );
        restoreStreams();

        assertThat(exitCode).isZero();

        AgentRunSummary run = runRepository.findById("run-audit-fake-tool").orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);

        List<AgentMessageDto> messages = messageRepository.findByRunId("run-audit-fake-tool");
        boolean hasToolObservation = messages.stream()
            .anyMatch(m -> m.role() == Role.USER && m.toolCallId() != null);
        assertThat(hasToolObservation).isTrue();
    }

    @Test
    void engineFakeToolFailureMarksRunAsFailed() {
        int exitCode = commandLine().execute(
            "--prompt", "read missing",
            "--dir", tempDir.toString(),
            "--session", "audit-fake-fail",
            "--engine", "fake"
        );
        restoreStreams();

        assertThat(exitCode).isEqualTo(1);

        AgentRunSummary run = runRepository.findById("run-audit-fake-fail").orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.errorReason()).isNotBlank();
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

        AgentRunSummary run = runRepository.findById("run-audit-fake-llm-fail").orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
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

        AgentRunSummary run = runRepository.findById("run-audit-plan-success").orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(run.mode()).isEqualTo("plan");

        List<ToolExecutionRecord> executions = toolExecutionRepository.findByRunId("run-audit-plan-success");
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

        AgentRunSummary run = runRepository.findById("run-audit-plan-fail").orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);

        List<ToolExecutionRecord> executions = toolExecutionRepository.findByRunId("run-audit-plan-fail");
        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).stepId()).isEqualTo("fail-step");
        assertThat(executions.get(0).isError()).isTrue();
    }

}

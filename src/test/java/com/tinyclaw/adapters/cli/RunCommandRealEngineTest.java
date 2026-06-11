package com.tinyclaw.adapters.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.AgentRunResult;
import com.tinyclaw.adapters.reporter.NoOpReporter;
import com.tinyclaw.application.run.AgentRunExecutionService;
import com.tinyclaw.application.run.ScriptedRunExecutor;
import com.tinyclaw.config.TinyClawModelProperties;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.Role;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.Usage;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmResponse;
import com.tinyclaw.ports.persistence.RunRepositoryPort;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import com.tinyclaw.ports.session.SessionService;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunCommandRealEngineTest {

    private final ScriptedRunExecutor scriptedRunExecutor = mock(ScriptedRunExecutor.class);
    private final AgentEngine agentEngine = mock(AgentEngine.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SessionService sessionService = mock(SessionService.class);
    private final RunRepositoryPort runRepository = mock(RunRepositoryPort.class);
    private final ToolExecutionRepositoryPort toolExecutionRepository = mock(ToolExecutionRepositoryPort.class);

    private RunCommand createCommand(TinyClawModelProperties properties) {
        return new RunCommand(
            new AgentRunExecutionService(runRepository, null, sessionService, objectMapper, new NoOpReporter()),
            scriptedRunExecutor, agentEngine, objectMapper,
            runRepository, toolExecutionRepository, properties,
            new com.tinyclaw.config.AgentProperties());
    }

    @Test
    void realEngineFailsWhenNotEnabled() {
        TinyClawModelProperties properties = new TinyClawModelProperties();
        properties.setEnabled(false);

        RunCommand cmd = createCommand(properties);
        CommandLine cl = new CommandLine(cmd);
        int exit = cl.execute("--prompt", "hello", "--engine", "real", "--dir", ".");

        assertThat(exit).isEqualTo(2);
    }

    @Test
    void realEngineFailsWhenApiKeyMissing() {
        TinyClawModelProperties properties = new TinyClawModelProperties();
        properties.setEnabled(true);
        properties.setApiKey("");

        RunCommand cmd = createCommand(properties);
        CommandLine cl = new CommandLine(cmd);
        int exit = cl.execute("--prompt", "hello", "--engine", "real", "--dir", ".");

        assertThat(exit).isEqualTo(2);
    }

    @Test
    void fakeEngineStillWorks() {
        TinyClawModelProperties properties = new TinyClawModelProperties();
        AgentEngine fakeEngine = mock(AgentEngine.class);
        when(fakeEngine.run(any(), any(), any(), any(), any())).thenReturn(
            new AgentRunResult(true, "Done", 1, null)
        );
        when(agentEngine.withLlmGateway(any())).thenReturn(fakeEngine);

        RunCommand cmd = createCommand(properties);
        CommandLine cl = new CommandLine(cmd);
        int exit = cl.execute("--prompt", "hello", "--engine", "fake", "--dir", ".");

        assertThat(exit).isEqualTo(0);
    }

    @Test
    void noneEngineStillWorks() {
        TinyClawModelProperties properties = new TinyClawModelProperties();

        RunCommand cmd = createCommand(properties);
        CommandLine cl = new CommandLine(cmd);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cl.execute("--prompt", "hello", "--engine", "none", "--dir", ".");
            assertThat(exit).isEqualTo(0);
            assertThat(out.toString()).contains("prompt: hello");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void invalidEngineReturnsExitCode2() {
        RunCommand cmd = createCommand(new TinyClawModelProperties());
        CommandLine cl = new CommandLine(cmd);
        int exit = cl.execute("--prompt", "hello", "--engine", "bogus", "--dir", ".");

        assertThat(exit).isEqualTo(2);
    }

    @Test
    void realEngineWithStubGatewayExecutesAndShowsUsage() {
        TinyClawModelProperties properties = new TinyClawModelProperties();
        properties.setEnabled(true);
        properties.setApiKey("sk-test");
        properties.setName("test-model");
        properties.setPricing(new TinyClawModelProperties.Pricing());
        properties.getPricing().setInputPricePer1M(0.15);
        properties.getPricing().setOutputPricePer1M(0.15);

        AgentEngine namedEngine = mock(AgentEngine.class);
        when(agentEngine.withModelName("test-model")).thenReturn(namedEngine);
        when(namedEngine.run(any(), any(), any(), any(), any())).thenReturn(
            new AgentRunResult(true, "Done", 2, null, new Usage(100, 50))
        );
        when(sessionService.getWorkingMemory(any())).thenReturn(List.of());

        RunCommand cmd = createCommand(properties);
        CommandLine cl = new CommandLine(cmd);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cl.execute("--prompt", "hello", "--engine", "real", "--dir", ".");
            assertThat(exit).isEqualTo(0);
            String output = out.toString();
            assertThat(output).contains("usage:");
            assertThat(output).contains("promptTokens: 100");
            assertThat(output).contains("completionTokens: 50");
            assertThat(output).contains("totalTokens: 150");
            assertThat(output).contains("estimatedCost:");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void realEngineWithDeepseekV4FlashModelName() {
        TinyClawModelProperties properties = new TinyClawModelProperties();
        properties.setEnabled(true);
        properties.setApiKey("sk-test");
        properties.setName("deepseek-v4-flash");

        AgentEngine namedEngine = mock(AgentEngine.class);
        when(agentEngine.withModelName("deepseek-v4-flash")).thenReturn(namedEngine);
        when(namedEngine.run(any(), any(), any(), any(), any())).thenReturn(
            new AgentRunResult(true, "Done", 1, null)
        );
        when(sessionService.getWorkingMemory(any())).thenReturn(List.of());

        RunCommand cmd = createCommand(properties);
        CommandLine cl = new CommandLine(cmd);

        int exit = cl.execute("--prompt", "hello", "--engine", "real", "--dir", ".");
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void usageCostSummaryNotShownWhenPricingIsZero() {
        TinyClawModelProperties properties = new TinyClawModelProperties();
        properties.setEnabled(true);
        properties.setApiKey("sk-test");
        properties.setName("test-model");
        // pricing defaults to 0.0

        AgentEngine namedEngine = mock(AgentEngine.class);
        when(agentEngine.withModelName("test-model")).thenReturn(namedEngine);
        when(namedEngine.run(any(), any(), any(), any(), any())).thenReturn(
            new AgentRunResult(true, "Done", 2, null, new Usage(100, 50))
        );
        when(sessionService.getWorkingMemory(any())).thenReturn(List.of());

        RunCommand cmd = createCommand(properties);
        CommandLine cl = new CommandLine(cmd);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cl.execute("--prompt", "hello", "--engine", "real", "--dir", ".");
            assertThat(exit).isEqualTo(0);
            String output = out.toString();
            assertThat(output).contains("usage:");
            assertThat(output).doesNotContain("estimatedCost:");
        } finally {
            System.setOut(originalOut);
        }
    }
}

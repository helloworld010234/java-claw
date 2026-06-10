package com.tinyclaw.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.AgentRunResult;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the real LLM engine wiring path.
 * Uses a local OpenAI-compatible HTTP stub so the Spring AI adapter, timeout,
 * retry, and observation layers are exercised without reaching a real provider.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tiny-claw.model.enabled=true",
    "tiny-claw.model.name=integration-test-model",
    "tiny-claw.model.api-key=sk-integration-test",
    "tiny-claw.model.request-timeout-seconds=2",
    "tiny-claw.model.max-retry-attempts=0",
    "tiny-claw.model.retry-backoff-ms=0"
})
class RealEngineIntegrationTest {

    private static final AtomicReference<String> LAST_REQUEST_PATH = new AtomicReference<>();
    private static final AtomicReference<String> LAST_REQUEST_BODY = new AtomicReference<>();
    private static final HttpServer MOCK_SERVER = createMockServer();

    @Autowired
    private AgentEngine agentEngine;

    @Autowired
    private LlmGateway llmGateway;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("tiny-claw.model.base-url",
            () -> "http://localhost:" + MOCK_SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        MOCK_SERVER.stop(0);
    }

    @Test
    void contextLoadsWithSingleLlmGateway() {
        assertThat(llmGateway).isNotNull();
    }

    @Test
    void agentEngineIsInjected() {
        assertThat(agentEngine).isNotNull();
    }

    @Test
    void agentEngineUsesConfiguredModelNameAgainstLocalEndpoint() {
        LAST_REQUEST_PATH.set(null);
        LAST_REQUEST_BODY.set(null);

        AgentRun run = AgentRun.start("run-1", "session-1", 3, Instant.now());
        Session session = Session.create(
            "session-1",
            Paths.get(".").toAbsolutePath().toString(),
            Instant.now()
        );

        AgentRunResult result = agentEngine
            .withModelName("integration-test-model")
            .run(run, session, "Hello", new ToolExecutionContext(Paths.get(".")));

        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.finalMessage()).isEqualTo("Stub response");
        assertThat(LAST_REQUEST_PATH.get()).contains("chat/completions");
        assertThat(LAST_REQUEST_BODY.get()).contains("\"model\":\"integration-test-model\"");
    }

    private static HttpServer createMockServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", RealEngineIntegrationTest::handleExchange);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to start local LLM stub server", e);
        }
    }

    private static void handleExchange(HttpExchange exchange) throws IOException {
        LAST_REQUEST_PATH.set(exchange.getRequestURI().getPath());
        LAST_REQUEST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        byte[] response = """
            {
              "id": "chatcmpl-test",
              "object": "chat.completion",
              "created": 1,
              "model": "integration-test-model",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": "Stub response"
                  },
                  "finish_reason": "stop"
                }
              ],
              "usage": {
                "prompt_tokens": 5,
                "completion_tokens": 3,
                "total_tokens": 8
              }
            }
            """.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}

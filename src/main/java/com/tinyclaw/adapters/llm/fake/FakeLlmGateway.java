package com.tinyclaw.adapters.llm.fake;

import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmException;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

/**
 * Fake LLM gateway for deterministic testing and local CLI runs.
 *
 * <p>Supports two modes:</p>
 * <ul>
 *   <li>Pre-programmed sequence: consumes a fixed list of responses in order.</li>
 *   <li>Dynamic handler: delegates to a {@link Function} for request-driven responses.</li>
 * </ul>
 *
 * <p>Every request is recorded so tests can assert on conversation history.</p>
 */
public class FakeLlmGateway implements LlmGateway {

    private final Function<LlmRequest, LlmResponse> handler;
    private final Queue<LlmResponse> responses;
    private final List<LlmRequest> recordedRequests = Collections.synchronizedList(new ArrayList<>());

    /**
     * Pre-programmed sequence mode.
     */
    public FakeLlmGateway(List<LlmResponse> responses) {
        Objects.requireNonNull(responses, "responses must not be null");
        this.responses = new ConcurrentLinkedQueue<>(responses);
        this.handler = request -> {
            LlmResponse response = this.responses.poll();
            if (response == null) {
                throw new LlmException("No more fake responses programmed");
            }
            return response;
        };
    }

    /**
     * Dynamic handler mode.
     */
    public FakeLlmGateway(Function<LlmRequest, LlmResponse> handler) {
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
        this.responses = null;
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        recordedRequests.add(request);
        return handler.apply(request);
    }

    /**
     * Returns all requests received so far.
     */
    public List<LlmRequest> recordedRequests() {
        return List.copyOf(recordedRequests);
    }

    /**
     * Returns true if all programmed responses have been consumed.
     * Always returns true in dynamic handler mode.
     */
    public boolean isExhausted() {
        return responses == null || responses.isEmpty();
    }

    /**
     * Creates a simple keyword-driven fake gateway for CLI smoke tests.
     *
     * @param prompt the user prompt used to select a scenario
     */
    public static FakeLlmGateway forPrompt(String prompt) {
        String lower = prompt.toLowerCase();
        if (lower.contains("fail") || lower.contains("error")) {
            return new FakeLlmGateway(req -> {
                throw new LlmException("Simulated LLM failure");
            });
        }
        if (lower.contains("write") || lower.contains("create")) {
            return new FakeLlmGateway(List.of(
                new LlmResponse("", List.of(
                    ToolCall.of("t1", "write_file",
                        "{\"path\":\"fake-out.txt\",\"content\":\"from-fake-llm\"}")
                ), null),
                new LlmResponse("Done", List.of(), null)
            ));
        }
        if (lower.contains("read")) {
            return new FakeLlmGateway(List.of(
                new LlmResponse("", List.of(
                    ToolCall.of("t1", "read_file", "{\"path\":\"missing-for-read.txt\"}")
                ), null),
                new LlmResponse("Could not read", List.of(), null)
            ));
        }
        return new FakeLlmGateway(List.of(
            new LlmResponse("Fake response to: " + prompt, List.of(), null)
        ));
    }
}

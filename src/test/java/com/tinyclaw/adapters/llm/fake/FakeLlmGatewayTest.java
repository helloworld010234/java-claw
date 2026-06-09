package com.tinyclaw.adapters.llm.fake;

import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolDefinition;
import com.tinyclaw.ports.llm.LlmException;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmRequestOptions;
import com.tinyclaw.ports.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeLlmGatewayTest {

    @Test
    void returnsResponsesInOrder() {
        FakeLlmGateway gateway = new FakeLlmGateway(List.of(
            new LlmResponse("first", List.of(), null),
            new LlmResponse("second", List.of(), null)
        ));

        LlmRequest request = dummyRequest();

        assertThat(gateway.generate(request).content()).isEqualTo("first");
        assertThat(gateway.generate(request).content()).isEqualTo("second");
        assertThat(gateway.isExhausted()).isTrue();
    }

    @Test
    void recordsAllRequests() {
        FakeLlmGateway gateway = new FakeLlmGateway(List.of(
            new LlmResponse("ok", List.of(), null),
            new LlmResponse("ok", List.of(), null)
        ));

        LlmRequest req1 = new LlmRequest("m1", List.of(), List.of(), LlmRequestOptions.defaults());
        LlmRequest req2 = new LlmRequest("m2", List.of(), List.of(), LlmRequestOptions.defaults());

        gateway.generate(req1);
        gateway.generate(req2);

        assertThat(gateway.recordedRequests()).hasSize(2);
        assertThat(gateway.recordedRequests().get(0).model()).isEqualTo("m1");
        assertThat(gateway.recordedRequests().get(1).model()).isEqualTo("m2");
    }

    @Test
    void throwsWhenExhausted() {
        FakeLlmGateway gateway = new FakeLlmGateway(List.of());

        assertThatThrownBy(() -> gateway.generate(dummyRequest()))
            .isInstanceOf(LlmException.class)
            .hasMessageContaining("No more fake responses");
    }

    @Test
    void dynamicHandlerReturnsComputedResponse() {
        FakeLlmGateway gateway = new FakeLlmGateway(req ->
            new LlmResponse("echo: " + req.model(), List.of(), null)
        );

        LlmRequest req = new LlmRequest("alpha", List.of(), List.of(), LlmRequestOptions.defaults());
        LlmResponse response = gateway.generate(req);

        assertThat(response.content()).isEqualTo("echo: alpha");
    }

    @Test
    void forPromptDefaultReturnsText() {
        FakeLlmGateway gateway = FakeLlmGateway.forPrompt("hello world");
        LlmResponse response = gateway.generate(dummyRequest());

        assertThat(response.content()).isEqualTo("Fake response to: hello world");
        assertThat(response.hasToolCalls()).isFalse();
    }

    @Test
    void forPromptWriteReturnsToolCall() {
        FakeLlmGateway gateway = FakeLlmGateway.forPrompt("write file");
        LlmResponse first = gateway.generate(dummyRequest());

        assertThat(first.hasToolCalls()).isTrue();
        assertThat(first.toolCalls().get(0).name()).isEqualTo("write_file");
    }

    @Test
    void forPromptFailThrowsException() {
        FakeLlmGateway gateway = FakeLlmGateway.forPrompt("trigger fail");

        assertThatThrownBy(() -> gateway.generate(dummyRequest()))
            .isInstanceOf(LlmException.class)
            .hasMessageContaining("Simulated LLM failure");
    }

    @Test
    void dynamicHandlerRecordsRequests() {
        FakeLlmGateway gateway = new FakeLlmGateway(req ->
            new LlmResponse("ok", List.of(), null)
        );

        gateway.generate(new LlmRequest("a", List.of(), List.of(), LlmRequestOptions.defaults()));
        gateway.generate(new LlmRequest("b", List.of(), List.of(), LlmRequestOptions.defaults()));

        assertThat(gateway.recordedRequests()).hasSize(2);
    }

    private LlmRequest dummyRequest() {
        return new LlmRequest("test", List.of(), List.of(), LlmRequestOptions.defaults());
    }
}

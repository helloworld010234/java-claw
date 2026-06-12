package com.tinyclaw.adapters.benchmark;

import com.tinyclaw.adapters.llm.fake.FakeLlmGateway;
import com.tinyclaw.application.benchmark.BenchmarkSuite;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmRequestOptions;
import com.tinyclaw.ports.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkFakeLlmFactoryTest {

    @Test
    void editConfigDriverProducesEditFileCall() {
        FakeLlmGateway gateway = BenchmarkFakeLlmFactory.forCase(BenchmarkSuite.editConfigCase());

        LlmResponse first = gateway.generate(new LlmRequest("fake", List.of(), List.of(), LlmRequestOptions.defaults()));
        assertThat(first.hasToolCalls()).isTrue();
        ToolCall call = first.toolCalls().get(0);
        assertThat(call.name()).isEqualTo("edit_file");
        assertThat(call.argumentsJson()).contains("v2.0.0");

        LlmResponse second = gateway.generate(new LlmRequest("fake", List.of(), List.of(), LlmRequestOptions.defaults()));
        assertThat(second.hasToolCalls()).isFalse();
    }

    @Test
    void writeMathTestDriverProducesReadThenWriteCalls() {
        FakeLlmGateway gateway = BenchmarkFakeLlmFactory.forCase(BenchmarkSuite.writeMathTestCase());

        LlmResponse first = gateway.generate(new LlmRequest("fake", List.of(), List.of(), LlmRequestOptions.defaults()));
        assertThat(first.hasToolCalls()).isTrue();
        assertThat(first.toolCalls().get(0).name()).isEqualTo("read_file");

        LlmResponse second = gateway.generate(new LlmRequest("fake", List.of(), List.of(), LlmRequestOptions.defaults()));
        assertThat(second.hasToolCalls()).isTrue();
        ToolCall writeCall = second.toolCalls().get(0);
        assertThat(writeCall.name()).isEqualTo("write_file");
        assertThat(writeCall.argumentsJson()).contains("math_test.go");
        assertThat(writeCall.argumentsJson()).contains("TestMultiply");

        LlmResponse third = gateway.generate(new LlmRequest("fake", List.of(), List.of(), LlmRequestOptions.defaults()));
        assertThat(third.hasToolCalls()).isFalse();
    }
}

package com.tinyclaw.adapters.benchmark;

import com.tinyclaw.adapters.llm.fake.FakeLlmGateway;
import com.tinyclaw.application.benchmark.BenchmarkCase;
import com.tinyclaw.application.benchmark.BenchmarkSuite;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.ports.llm.LlmResponse;

import java.util.List;

/**
 * Produces deterministic fake LLM drivers for benchmark cases.
 *
 * <p>Each driver returns a fixed sequence of assistant responses and tool calls
 * so that benchmark runs are reproducible without a real API key.</p>
 */
public final class BenchmarkFakeLlmFactory {

    private BenchmarkFakeLlmFactory() {
        // utility class
    }

    /**
     * Creates a fake LLM gateway for the given benchmark case.
     *
     * @param benchmarkCase the case to drive
     * @return a fake gateway with a pre-programmed response sequence
     * @throws IllegalArgumentException if the case is not supported
     */
    public static FakeLlmGateway forCase(BenchmarkCase benchmarkCase) {
        return switch (benchmarkCase.id()) {
            case BenchmarkSuite.EDIT_CONFIG_CASE_ID -> editConfigDriver();
            case BenchmarkSuite.WRITE_TEST_CASE_ID -> writeMathTestDriver();
            case BenchmarkSuite.FAILING_CASE_ID -> failingDriver();
            default -> throw new IllegalArgumentException(
                "Unsupported benchmark case for fake LLM: " + benchmarkCase.id()
            );
        };
    }

    private static FakeLlmGateway editConfigDriver() {
        return new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("tc-1", "edit_file", """
                    {
                      "path": "config.json",
                      "oldText": "\\"version\\": \\"v1.0.0\\"",
                      "newText": "\\"version\\": \\"v2.0.0\\""
                    }
                    """)
            ), null),
            new LlmResponse("Version updated.", List.of(), null)
        ));
    }

    private static FakeLlmGateway failingDriver() {
        return new FakeLlmGateway(List.of(
            new LlmResponse("Done.", List.of(), null)
        ));
    }

    private static FakeLlmGateway writeMathTestDriver() {
        return new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("tc-1", "read_file", """
                    {"path": "math.go"}
                    """)
            ), null),
            new LlmResponse("", List.of(
                ToolCall.of("tc-2", "write_file", """
                    {
                      "path": "math_test.go",
                      "content": "package math\\n\\nimport \\"testing\\"\\n\\nfunc TestMultiply(t *testing.T) {\\n\\tcases := []struct{ a, b, want int }{\\n\\t\\t{2, 3, 6},\\n\\t\\t{0, 5, 0},\\n\\t\\t{-1, 8, -8},\\n\\t}\\n\\tfor _, c := range cases {\\n\\t\\tgot := Multiply(c.a, c.b)\\n\\t\\tif got != c.want {\\n\\t\\t\\tt.Errorf(\\"Multiply(%d,%d) = %d, want %d\\", c.a, c.b, got, c.want)\\n\\t\\t}\\n\\t}\\n}\\n"
                    }
                    """)
            ), null),
            new LlmResponse("Test file created.", List.of(), null)
        ));
    }
}

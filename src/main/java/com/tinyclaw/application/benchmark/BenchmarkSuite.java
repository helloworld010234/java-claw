package com.tinyclaw.application.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;


/**
 * Predefined benchmark cases aligned with the Go reference benchmark suite.
 */
public final class BenchmarkSuite {

    public static final String EDIT_CONFIG_CASE_ID = "test_001_edit";
    public static final String WRITE_TEST_CASE_ID = "test_002_code_gen";

    private static final String CONFIG_JSON = "{\"name\": \"tiny-claw\", \"version\": \"v1.0.0\"}";

    private static final String MATH_GO = """
        package math

        func Multiply(a, b int) int {
        \treturn a * b
        }
        """;

    private BenchmarkSuite() {
        // utility class
    }

    public static final String FAILING_CASE_ID = "test_003_fail";

    /**
     * Returns the default benchmark cases.
     */
    public static List<BenchmarkCase> defaultCases() {
        return List.of(editConfigCase(), writeMathTestCase());
    }

    /**
     * Returns all benchmark cases including the intentionally failing case.
     * Useful for testing failure reporting.
     */
    public static List<BenchmarkCase> allCases() {
        return List.of(editConfigCase(), writeMathTestCase(), failingCase());
    }

    /**
     * Intentionally failing case for testing failure reporting.
     */
    public static BenchmarkCase failingCase() {
        return new BenchmarkCase(
            FAILING_CASE_ID,
            "Intentionally failing case",
            "This case is expected to fail validation.",
            workspace -> writeString(workspace.resolve("x.txt"), "x"),
            workspace -> {
                throw new BenchmarkException("Intentional validation failure", null);
            }
        );
    }

    /**
     * Case 1: the agent must edit config.json and change the version field.
     */
    public static BenchmarkCase editConfigCase() {
        return new BenchmarkCase(
            EDIT_CONFIG_CASE_ID,
            "Edit config.json version field",
            """
                Current directory has a config.json. Use edit_file to change \"version\" \
                from \"v1.0.0\" to \"v2.0.0\". Do nothing else.
                """,
            workspace -> writeString(workspace.resolve("config.json"), CONFIG_JSON),
            workspace -> {
                String content = readString(workspace.resolve("config.json"));
                requireContains(content, "\"version\": \"v2.0.0\"", "config.json");
            }
        );
    }

    /**
     * Case 2: the agent must read math.go and create math_test.go with a TestMultiply case.
     */
    public static BenchmarkCase writeMathTestCase() {
        return new BenchmarkCase(
            WRITE_TEST_CASE_ID,
            "Create math_test.go for Multiply",
            """
                Current directory has a math.go. Read it carefully, then create a standard \
                unit test file math_test.go to test the Multiply function. Include normal test cases.
                """,
            workspace -> writeString(workspace.resolve("math.go"), MATH_GO),
            workspace -> {
                Path testFile = workspace.resolve("math_test.go");
                if (!Files.exists(testFile)) {
                    throw new BenchmarkException("math_test.go was not created", null);
                }
                String content = readString(testFile);
                requireContains(content, "TestMultiply", "math_test.go");
                requireContains(content, "Multiply(", "math_test.go");
            }
        );
    }

    private static void writeString(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new BenchmarkException("Failed to write fixture " + path, e);
        }
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new BenchmarkException("Failed to read file " + path, e);
        }
    }

    private static void requireContains(String content, String expected, String fileName) {
        if (content == null || !content.contains(expected)) {
            throw new BenchmarkException(
                "Expected " + fileName + " to contain '" + expected + "'", null);
        }
    }
}

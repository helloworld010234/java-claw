package com.tinyclaw.application.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    public static final String FAILING_CASE_ID = "test_003_fail";

    private static final String CONFIG_JSON = "{\"name\": \"tiny-claw\", \"version\": \"v1.0.0\"}";

    private static final String MATH_GO = """
        package math

        func Multiply(a, b int) int {
        \treturn a * b
        }
        """;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private BenchmarkSuite() {
        // utility class
    }

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
     *
     * <p>Validation asserts that the file remains valid JSON, the {@code name}
     * field is unchanged, and {@code version} is exactly {@code v2.0.0}.</p>
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
                JsonNode root;
                try {
                    root = OBJECT_MAPPER.readTree(content);
                } catch (IOException e) {
                    throw new BenchmarkException("config.json is not valid JSON: " + e.getMessage(), e);
                }
                if (!root.isObject()) {
                    throw new BenchmarkException("config.json root is not a JSON object", null);
                }
                JsonNode nameNode = root.get("name");
                if (nameNode == null || !"tiny-claw".equals(nameNode.asText())) {
                    throw new BenchmarkException("config.json name field was corrupted or removed", null);
                }
                JsonNode versionNode = root.get("version");
                if (versionNode == null || !"v2.0.0".equals(versionNode.asText())) {
                    throw new BenchmarkException(
                        "Expected config.json version to be exactly 'v2.0.0', got: "
                            + (versionNode == null ? "missing" : versionNode.asText()), null);
                }
            }
        );
    }

    /**
     * Case 2: the agent must read math.go and create math_test.go with a TestMultiply case.
     *
     * <p>Validation checks that the generated file is a syntactically plausible Go test
     * containing the required package, imports, test function, function call and an
     * assertion or table-driven test case.</p>
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
                requireContains(content, "package math", "math_test.go");
                requireContains(content, "import \"testing\"", "math_test.go");
                requireContains(content, "TestMultiply", "math_test.go");
                requireContains(content, "Multiply(", "math_test.go");
                if (!hasAssertionOrTableCase(content)) {
                    throw new BenchmarkException(
                        "math_test.go must contain a multiplication assertion or table-driven test case", null);
                }
            }
        );
    }

    private static boolean hasAssertionOrTableCase(String content) {
        String normalized = content.toLowerCase();
        boolean hasTableCase = normalized.contains("cases :=") || normalized.contains("[]struct");
        boolean hasAssertion = normalized.contains("t.errorf") || normalized.contains("t.fatalf")
            || normalized.contains("if got !=") || normalized.contains("reflect.deepequal");
        return hasTableCase || hasAssertion;
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

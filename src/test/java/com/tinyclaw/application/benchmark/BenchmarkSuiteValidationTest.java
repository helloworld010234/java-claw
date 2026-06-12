package com.tinyclaw.application.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkSuiteValidationTest {

    @TempDir
    Path tempDir;

    @Test
    void editConfigValidationAcceptsCorrectUpdate() throws IOException {
        Files.writeString(tempDir.resolve("config.json"),
            "{\"name\": \"tiny-claw\", \"version\": \"v2.0.0\"}");

        BenchmarkSuite.editConfigCase().validation().accept(tempDir);
    }

    @Test
    void editConfigValidationRejectsInvalidJson() throws IOException {
        Files.writeString(tempDir.resolve("config.json"), "{not json");

        assertThatThrownBy(() -> BenchmarkSuite.editConfigCase().validation().accept(tempDir))
            .isInstanceOf(BenchmarkException.class)
            .hasMessageContaining("valid JSON");
    }

    @Test
    void editConfigValidationRejectsCorruptedName() throws IOException {
        Files.writeString(tempDir.resolve("config.json"),
            "{\"name\": \"broken\", \"version\": \"v2.0.0\"}");

        assertThatThrownBy(() -> BenchmarkSuite.editConfigCase().validation().accept(tempDir))
            .isInstanceOf(BenchmarkException.class)
            .hasMessageContaining("name");
    }

    @Test
    void editConfigValidationRejectsWrongVersion() throws IOException {
        Files.writeString(tempDir.resolve("config.json"),
            "{\"name\": \"tiny-claw\", \"version\": \"v2.0.1\"}");

        assertThatThrownBy(() -> BenchmarkSuite.editConfigCase().validation().accept(tempDir))
            .isInstanceOf(BenchmarkException.class)
            .hasMessageContaining("v2.0.0");
    }

    @Test
    void writeMathTestValidationAcceptsValidTest() throws IOException {
        Files.writeString(tempDir.resolve("math_test.go"), """
            package math

            import "testing"

            func TestMultiply(t *testing.T) {
                if Multiply(2, 3) != 6 {
                    t.Errorf("wrong")
                }
            }
            """);

        BenchmarkSuite.writeMathTestCase().validation().accept(tempDir);
    }

    @Test
    void writeMathTestValidationRejectsMissingTestMultiply() throws IOException {
        Files.writeString(tempDir.resolve("math_test.go"), """
            package math

            import "testing"

            func TestAdd(t *testing.T) {}
            """);

        assertThatThrownBy(() -> BenchmarkSuite.writeMathTestCase().validation().accept(tempDir))
            .isInstanceOf(BenchmarkException.class)
            .hasMessageContaining("TestMultiply");
    }

    @Test
    void writeMathTestValidationRejectsMissingMultiplyCall() throws IOException {
        Files.writeString(tempDir.resolve("math_test.go"), """
            package math

            import "testing"

            func TestOther(t *testing.T) {}
            """);

        assertThatThrownBy(() -> BenchmarkSuite.writeMathTestCase().validation().accept(tempDir))
            .isInstanceOf(BenchmarkException.class)
            .hasMessageContaining("TestMultiply");
    }

    @Test
    void writeMathTestValidationRejectsMissingAssertion() throws IOException {
        Files.writeString(tempDir.resolve("math_test.go"), """
            package math

            import "testing"

            func TestMultiply(t *testing.T) {
                Multiply(2, 3)
            }
            """);

        assertThatThrownBy(() -> BenchmarkSuite.writeMathTestCase().validation().accept(tempDir))
            .isInstanceOf(BenchmarkException.class)
            .hasMessageContaining("assertion");
    }

    @Test
    void writeMathTestValidationAcceptsTableDrivenTest() throws IOException {
        Files.writeString(tempDir.resolve("math_test.go"), """
            package math

            import "testing"

            func TestMultiply(t *testing.T) {
                cases := []struct{ a, b, want int }{
                    {2, 3, 6},
                }
                for _, c := range cases {
                    if got := Multiply(c.a, c.b); got != c.want {
                        t.Errorf("Multiply(%d,%d) = %d, want %d", c.a, c.b, got, c.want)
                    }
                }
            }
            """);

        BenchmarkSuite.writeMathTestCase().validation().accept(tempDir);
    }
}

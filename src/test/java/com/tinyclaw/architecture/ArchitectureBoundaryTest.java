package com.tinyclaw.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture boundary validation tests.
 *
 * <p>Enforces hexagonal / clean architecture layering:</p>
 * <ul>
 *   <li>{@code application} must not reference {@code adapters} or {@code config} or {@code org.springframework}</li>
 *   <li>{@code domain} must not reference Spring, config, adapters, or application</li>
 *   <li>{@code ports} must not reference {@code application}, {@code adapters}, {@code config}, or {@code org.springframework}</li>
 * </ul>
 */
class ArchitectureBoundaryTest {

    private static final Path SRC_MAIN = Paths.get("src/main/java/com/tinyclaw");

    @Test
    void applicationLayerDoesNotDependOnAdaptersOrSpringOrConfig() throws IOException {
        Path applicationDir = SRC_MAIN.resolve("application");
        assertThat(applicationDir).exists();

        List<String> violations = scanForForbiddenReference(applicationDir,
            "com.tinyclaw.adapters", "com.tinyclaw.config", "org.springframework");
        assertThat(violations)
            .withFailMessage("Application layer must not depend on adapters, config, or Spring:%n%s", String.join("%n", violations))
            .isEmpty();
    }

    @Test
    void domainLayerDoesNotDependOnSpringConfigAdaptersOrApplication() throws IOException {
        Path domainDir = SRC_MAIN.resolve("domain");
        assertThat(domainDir).exists();

        List<String> violations = scanForForbiddenReference(domainDir,
            "org.springframework", "com.tinyclaw.config", "com.tinyclaw.adapters", "com.tinyclaw.application");
        assertThat(violations)
            .withFailMessage("Domain layer must not depend on Spring, config, adapters, or application:%n%s", String.join("%n", violations))
            .isEmpty();
    }

    @Test
    void portsLayerDoesNotDependOnApplicationAdaptersConfigOrSpring() throws IOException {
        Path portsDir = SRC_MAIN.resolve("ports");
        assertThat(portsDir).exists();

        List<String> violations = scanForForbiddenReference(portsDir,
            "com.tinyclaw.application", "com.tinyclaw.adapters", "com.tinyclaw.config", "org.springframework");
        assertThat(violations)
            .withFailMessage("Ports layer must not depend on application, adapters, config, or Spring:%n%s", String.join("%n", violations))
            .isEmpty();
    }

    /**
     * Scans Java source files for both {@code import} statements and fully-qualified
     * class name references that start with any of the given forbidden prefixes.
     */
    private List<String> scanForForbiddenReference(Path dir, String... forbiddenPrefixes) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files
                .filter(p -> p.toString().endsWith(".java"))
                .flatMap(p -> {
                    try {
                        return Files.readAllLines(p).stream()
                            .map(String::trim)
                            .filter(line -> !line.startsWith("//") && !line.startsWith("*"))
                            .filter(line -> {
                                for (String prefix : forbiddenPrefixes) {
                                    if (line.contains(prefix)) {
                                        return true;
                                    }
                                }
                                return false;
                            })
                            .map(line -> p + ": " + line);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .toList();
        }
    }
}

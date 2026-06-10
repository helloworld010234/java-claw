package com.tinyclaw.config;

import com.tinyclaw.adapters.cli.RootCommand;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * CLI launcher that delegates filtered arguments to Picocli.
 *
 * <p>Runs after Spring Boot starts as a {@link CommandLineRunner}
 * and implements {@link ExitCodeGenerator} for proper process exit codes.</p>
 *
 * <p>Spring Boot property arguments are automatically stripped so only real CLI args reach Picocli.</p>
 */
@Component
public class CliRunner implements CommandLineRunner, ExitCodeGenerator {

    private static final Set<String> KNOWN_COMMANDS = Set.of("run", "tool", "runs", "approvals");
    private static final Set<String> CONFIG_PROPERTY_PREFIXES = Set.of(
        "--spring.", "--server.", "--management.", "--logging.", "--tinyclaw.", "--tiny-claw."
    );

    private final RootCommand rootCommand;
    private final CommandLine.IFactory factory;
    private int exitCode = 0;

    public CliRunner(RootCommand rootCommand, CommandLine.IFactory factory) {
        this.rootCommand = rootCommand;
        this.factory = factory;
    }

    @Override
    public void run(String... args) {
        String[] cliArgs = filterSpringArgs(args);

        if (containsCliCommand(cliArgs)) {
            exitCode = new CommandLine(rootCommand, factory).execute(cliArgs);
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    /**
     * Resets exit code for testing only.
     */
    void resetForTest() {
        this.exitCode = 0;
    }

    private static String[] filterSpringArgs(String[] args) {
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < args.length) {
            String arg = args[i];
            if (isConfigProperty(arg)) {
                // Handle space-separated format: --key value
                if (!arg.contains("=") && i + 1 < args.length) {
                    i++;
                }
                i++;
                continue;
            }
            result.add(arg);
            i++;
        }
        return result.toArray(new String[0]);
    }

    private static boolean isConfigProperty(String arg) {
        if (arg == null) {
            return false;
        }
        for (String prefix : CONFIG_PROPERTY_PREFIXES) {
            if (arg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsCliCommand(String[] args) {
        for (String arg : args) {
            if (arg != null && !arg.startsWith("--") && KNOWN_COMMANDS.contains(arg)) {
                return true;
            }
        }
        return false;
    }
}

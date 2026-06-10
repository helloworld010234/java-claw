package com.tinyclaw.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Argument normalizer executed before Spring Boot starts.
 *
 * <p>Converts space-separated config pairs (e.g. {@code --key value})
 * into the equals form ({@code --key=value}) that Spring Boot recognizes.</p>
 *
 * <p>Only known configuration prefixes are touched; Picocli business args are left unchanged.</p>
 */
public final class BootstrapArgs {

    private static final Set<String> CONFIG_PREFIXES = Set.of(
        "--spring.", "--server.", "--management.", "--logging.", "--tinyclaw.", "--tiny-claw."
    );

    private BootstrapArgs() {
        // utility class
    }

    /**
     * Normalizes space-separated config arguments into equals form.
     *
     * @param args raw command-line arguments, may be {@code null}
     * @return normalized array; {@code null} yields an empty array
     */
    public static String[] normalize(String[] args) {
        if (args == null) {
            return new String[0];
        }

        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < args.length) {
            String arg = args[i];
            if (isConfigProperty(arg) && !arg.contains("=")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    result.add(arg + "=" + args[i + 1]);
                    i += 2;
                    continue;
                }
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
        for (String prefix : CONFIG_PREFIXES) {
            if (arg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}

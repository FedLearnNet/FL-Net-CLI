package bio.cosy.flnet.cli.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads and writes docker compose style {@code KEY=value} files. */
public final class EnvFile {

    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private EnvFile() {
    }

    /**
     * Cryptographically random alphanumeric secret. Alphanumeric only, so the value never needs
     * quoting in env files, connection strings or shells.
     */
    public static String secret(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }

    /**
     * Writes the variables in insertion order with owner-only permissions (0600), since these
     * files contain secrets or deployment details.
     *
     * @param comments optional comment line written above the variable of the same key
     */
    public static void write(Path file, Map<String, ?> variables, Map<String, String> comments) {
        StringBuilder sb = new StringBuilder();
        variables.forEach((key, value) -> {
            if (comments.containsKey(key)) {
                sb.append("# ").append(comments.get(key)).append('\n');
            }
            sb.append(key).append('=').append(value == null ? "" : value).append('\n');
        });
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            FilePermissions.set(file, FilePermissions.OWNER_ONLY);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    public static void write(Path file, Map<String, ?> variables) {
        write(file, variables, Map.of());
    }

    /** Returns an empty map when the file does not exist. */
    public static Map<String, String> read(Path file) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return result;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.strip();
                int eq = trimmed.indexOf('=');
                if (trimmed.isEmpty() || trimmed.startsWith("#") || eq < 0) {
                    continue;
                }
                result.put(trimmed.substring(0, eq).strip(), trimmed.substring(eq + 1).strip());
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }
}

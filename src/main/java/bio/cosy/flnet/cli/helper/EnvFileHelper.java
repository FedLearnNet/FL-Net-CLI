package bio.cosy.flnet.cli.helper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EnvFileHelper {

    private EnvFileHelper() {
    }

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
            FilePermissionHelper.set(file, FilePermissionHelper.OWNER_ONLY);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    public static void write(Path file, Map<String, ?> variables) {
        write(file, variables, Map.of());
    }

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

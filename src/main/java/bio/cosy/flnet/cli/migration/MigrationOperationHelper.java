package bio.cosy.flnet.cli.migration;

import bio.cosy.flnet.cli.migration.MigrationCatalog.Task;
import bio.cosy.flnet.cli.helper.CliException;

import static bio.cosy.flnet.cli.migration.MigrationFileHelper.require;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.HashMap;
import java.util.regex.Pattern;

public final class MigrationOperationHelper {
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private MigrationOperationHelper() { }

    public static byte[] apply(Task op, byte[] before) {
        String context = op.getType() + " " + op.getPath();
        byte[] payload = op.getValue() == null ? null : op.getValue().getBytes(StandardCharsets.UTF_8);
        if (op.getType() == Task.Type.FILE_ADD) {
            require(payload != null && (before == null || Arrays.equals(before, payload)), context);
            return payload;
        }
        require(before != null, context + " (file missing)");
        if (op.getType() == Task.Type.FILE_REPLACE || op.getType() == Task.Type.FILE_REMOVE) {
            require(Objects.equals(text(before, context), op.getExpectedValue()), context);
            if (op.getType() == Task.Type.FILE_REPLACE) require(payload != null, context + " (new content missing)");
        }
        return switch (op.getType()) {
            case FILE_REPLACE -> payload;
            case FILE_REMOVE -> null;
            case TEXT_REPLACE -> {
                String text = text(before, context);
                String expected = op.getExpectedValue();
                require(expected != null && !expected.isEmpty() && op.getValue() != null, context + " (text missing)");
                int index = text.indexOf(expected);
                require(index >= 0 && text.indexOf(expected, index + expected.length()) < 0, context);
                yield (text.substring(0, index) + op.getValue() + text.substring(index + expected.length()))
                        .getBytes(StandardCharsets.UTF_8);
            }
            default -> editEnv(op, before, context);
        };
    }

    private static byte[] editEnv(Task op, byte[] before, String context) {
        require(op.getKey() != null && KEY.matcher(op.getKey()).matches(), context + " (invalid key)");
        if (op.getType() == Task.Type.ENV_RENAME) require(op.getNewKey() != null && KEY.matcher(op.getNewKey()).matches(), context + " (invalid new key)");
        if (op.getType() == Task.Type.ENV_ADD || op.getType() == Task.Type.ENV_SET) {
            require(op.getValue() != null && !op.getValue().contains("\n") && !op.getValue().contains("\r"), context + " (value must be one line)");
        }
        String text = text(before, context);
        String withoutCrLf = text.replace("\r\n", "");
        require(!withoutCrLf.contains("\r") && !(text.contains("\r\n") && withoutCrLf.contains("\n")),
                context + " (unsupported mixed line endings)");
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(text.split("\\r?\\n", -1)));
        var keys = new HashMap<String, Integer>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).stripLeading();
            if (line.isBlank() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            String key = eq < 0 ? "" : line.substring(0, eq).strip();
            require(KEY.matcher(key).matches() && !line.endsWith("\\"), context + " (unsupported env syntax)");
            require(keys.put(key, i) == null, context + " (duplicate key)");
        }
        Integer index = keys.get(op.getKey());
        if (op.getType() == Task.Type.ENV_ADD) {
            if (index != null) return before;
            lines.add(lines.getLast().isEmpty() ? lines.size() - 1 : lines.size(), op.getKey() + "=" + op.getValue());
        } else {
            require(index != null, context + " (key missing)");
            String line = lines.get(index);
            int eq = line.indexOf('=');
            if (op.getType() == Task.Type.ENV_RENAME) {
                require(!keys.containsKey(op.getNewKey()), context + " (target key exists)");
                lines.set(index, line.replaceFirst(Pattern.quote(op.getKey()), op.getNewKey()));
            } else {
                require(Objects.equals(line.substring(eq + 1), op.getExpectedValue()), context);
                if (op.getType() == Task.Type.ENV_REMOVE) lines.remove(index.intValue());
                else lines.set(index, line.substring(0, eq + 1) + op.getValue());
            }
        }
        return String.join(newline, lines).getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] bytes, String context) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw CliException.usage("Migration requires UTF-8 text: " + context);
        }
    }
}

package bio.cosy.flnet.cli.support;

import jakarta.inject.Singleton;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves every setting the same way: an explicit flag wins, otherwise the user is asked, and
 * without an interactive terminal (or with {@code --no-input}) the default is used. A setting
 * without default then fails with a hint naming the flag, so scripted installs never hang.
 */
@Singleton
public class Prompter {

    /** Returns an error message for invalid input, or {@code null} if the input is valid. */
    public interface Validator extends Function<String, String> {
        Validator ANY = value -> null;
        Validator NOT_EMPTY = value -> value.isBlank() ? "A value is required." : null;
    }

    private boolean interactive = hasTerminal();
    private boolean assumeYes;
    private BufferedReader reader;

    public void configure(InteractionOptions options) {
        interactive = !options.noInput && hasTerminal();
        assumeYes = options.yes;
    }

    /**
     * Since Java 22 {@code System.console()} also exists for redirected input, so only
     * {@code isTerminal()} tells whether a user can answer.
     */
    private static boolean hasTerminal() {
        Console console = System.console();
        return console != null && console.isTerminal();
    }

    public boolean isInteractive() {
        return interactive;
    }

    /** Free text setting. {@code def} may be {@code null} when there is no sensible default. */
    public String text(String flag, String given, String question, String def, Validator validator) {
        if (given != null) {
            return validated(flag, given.trim(), validator);
        }
        if (!interactive) {
            if (def == null) {
                throw CliException.usage("Missing value for " + flag + " (required when running without interactive input).");
            }
            return validated(flag, def, validator);
        }
        while (true) {
            String suffix = def == null || def.isEmpty() ? "" : " [" + def + "]";
            String answer = readLine(question + suffix + ": ").trim();
            if (answer.isEmpty() && def != null) {
                answer = def;
            }
            String error = validator.apply(answer);
            if (error == null) {
                return answer;
            }
            Ui.error(error);
        }
    }

    public int integer(String flag, Integer given, String question, int def, int min) {
        String value = text(flag, given == null ? null : String.valueOf(given), question, String.valueOf(def), input -> {
            try {
                return Integer.parseInt(input) < min ? "Must be at least " + min + "." : null;
            } catch (NumberFormatException e) {
                return "'" + input + "' is not a whole number.";
            }
        });
        return Integer.parseInt(value);
    }

    public boolean confirm(String flag, Boolean given, String question, boolean def) {
        if (given != null) {
            return given;
        }
        if (!interactive) {
            return def;
        }
        while (true) {
            String answer = readLine(question + (def ? " [Y/n]: " : " [y/N]: ")).trim().toLowerCase(Locale.ROOT);
            switch (answer) {
                case "" -> {
                    return def;
                }
                case "y", "yes" -> {
                    return true;
                }
                case "n", "no" -> {
                    return false;
                }
                default -> Ui.error("Please answer 'y' or 'n'.");
            }
        }
    }

    /**
     * Guard before a risky or destructive step. {@code --yes} accepts it, a non interactive run
     * without {@code --yes} refuses it.
     */
    public void requireConfirmation(String question) {
        if (assumeYes) {
            return;
        }
        if (!interactive) {
            throw CliException.usage(question + " Re-run with --yes to confirm non-interactively.");
        }
        if (!confirm("--yes", null, question, false)) {
            throw CliException.aborted();
        }
    }

    /** Shows an important notice and waits for Enter in interactive runs. */
    public void acknowledge(String notice) {
        Ui.warn(notice);
        if (interactive && !assumeYes) {
            readLine("Press Enter to continue...");
        }
    }

    /** Pick one option; the map key is what the user types (and what the flag accepts). */
    public <T> T choice(String flag, String given, String question, Map<String, T> options, String defKey) {
        Validator validator = value -> options.containsKey(value.toLowerCase(Locale.ROOT)) ? null
                : "Choose one of: " + String.join(", ", options.keySet()) + ".";
        String choices = options.keySet().stream().collect(Collectors.joining("/"));
        String key = text(flag, given, question + " (" + choices + ")", defKey, validator);
        return options.get(key.toLowerCase(Locale.ROOT));
    }

    /** Pick one of {@code options}; {@code key} gives what the user types (and what the flag accepts). */
    public <T> T choice(String flag, String given, String question, List<T> options, Function<T, String> key, T def) {
        Map<String, T> byKey = new LinkedHashMap<>();
        options.forEach(option -> byKey.put(key.apply(option), option));
        return choice(flag, given, question, byKey, key.apply(def));
    }

    /**
     * Secrets are never taken from flags (they would leak into shell history and process lists):
     * they come from a file, an environment variable, or a hidden prompt.
     */
    public String secret(String fileFlag, java.nio.file.Path file, String envVar, String question) {
        try {
            if (file != null) {
                String value = java.nio.file.Files.readString(file, StandardCharsets.UTF_8).strip();
                return validated(fileFlag, value, Validator.NOT_EMPTY);
            }
        } catch (IOException e) {
            throw CliException.usage("Cannot read " + fileFlag + " '" + file + "': " + e.getMessage());
        }
        String fromEnv = System.getenv(envVar);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        if (!interactive) {
            throw CliException.usage("Missing secret: pass " + fileFlag + " or set " + envVar + ".");
        }
        Console console = System.console();
        while (true) {
            char[] chars = console.readPassword("%s: ", question);
            if (chars == null) {
                throw CliException.aborted();
            }
            String value = new String(chars);
            if (!value.isBlank()) {
                return value;
            }
            Ui.error("A value is required.");
        }
    }

    private String validated(String flag, String value, Validator validator) {
        String error = validator.apply(value);
        if (error != null) {
            throw CliException.usage("Invalid value for " + flag + ": " + error);
        }
        return value;
    }

    private String readLine(String prompt) {
        System.out.print(prompt);
        System.out.flush();
        try {
            if (reader == null) {
                reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            }
            String line = reader.readLine();
            if (line == null) {
                // stdin closed (Ctrl+D)
                throw CliException.aborted();
            }
            return line;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package bio.cosy.flnet.cli.deploy.questionnaire;

import bio.cosy.flnet.cli.config.FLNetCliConfig;
import bio.cosy.flnet.cli.deploy.PortPlanner;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.helper.Prompter;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;

public abstract class BaseQuestionnaire {

    @Inject
    protected Prompter prompter;

    @Inject
    protected FLNetCliConfig config;

    protected static void section(String title, String... explanation) {
        ConsoleHelper.heading(title);
        for (String line : explanation) {
            ConsoleHelper.info(line);
        }
    }

    protected int port(PortPlanner ports, String flag, Integer given, String question, int current, int base) {
        int suggested = ports.suggest(current, base);
        if (given == null && suggested != current) {
            ConsoleHelper.info("Port " + current + " is taken on this machine, suggesting " + suggested + ".");
        }
        int chosen = Integer.parseInt(prompter.text(flag, text(given), question, String.valueOf(suggested), ports::validate));
        ports.warnIfBusy(chosen);
        return chosen;
    }

    protected Path file(String flag, Path given, String question, Path previous, boolean mustExist) {
        String value = prompter.text(flag, text(given), question, text(previous), input -> {
            if (input.isBlank()) {
                return "A file is required.";
            }
            return mustExist && !Files.isRegularFile(Path.of(input)) ? "'" + input + "' does not exist." : null;
        });
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            ConsoleHelper.warn("'" + path + "' does not exist (yet). It must exist before 'up'.");
        }
        return path;
    }

    protected static String text(Object value) {
        return value == null ? null : value.toString();
    }
}

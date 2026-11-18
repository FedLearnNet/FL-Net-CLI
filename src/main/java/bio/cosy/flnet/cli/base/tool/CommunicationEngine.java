package bio.cosy.flnet.cli.base.tool;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Which federated-learning communication layer a federated tool uses. */
public enum CommunicationEngine {
    FL_NET,
    FLOWER;

    public String cliName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static CommunicationEngine fromCli(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return Arrays.stream(values()).filter(engine -> engine.name().equals(normalized)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown communication engine '" + value + "'. Choose one of: " + cliNames() + "."));
    }

    public static String cliNames() {
        return Arrays.stream(values()).map(CommunicationEngine::cliName).collect(Collectors.joining(", "));
    }
}

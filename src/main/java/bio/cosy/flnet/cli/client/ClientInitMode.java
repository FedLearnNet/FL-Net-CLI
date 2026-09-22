package bio.cosy.flnet.cli.client;

import java.util.Locale;

public enum ClientInitMode {
    FRESH, //Nothing installed yet: generate everything,
    RECONFIGURE, //New answers, same secrets and data,
    CLEAN; // New secrets, which requires wiping all data;

    public String cliName() {
        return name().toLowerCase(Locale.ROOT);
    }
}

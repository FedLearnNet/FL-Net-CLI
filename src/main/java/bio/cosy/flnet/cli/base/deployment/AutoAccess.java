package bio.cosy.flnet.cli.base.deployment;

import java.util.Locale;

public enum AutoAccess {
    ALL, NONE, CERTIFIED_APPS;

    public String cliName() {
        return this == CERTIFIED_APPS ? "certified" : name().toLowerCase(Locale.ROOT);
    }

    // accepts the .env name (CERTIFIED_APPS) and the CLI name (certified)
    public static AutoAccess parse(String value, AutoAccess fallback) {
        if (value == null) {
            return fallback;
        }
        for (AutoAccess access : values()) {
            if (access.name().equalsIgnoreCase(value.strip()) || access.cliName().equalsIgnoreCase(value.strip())) {
                return access;
            }
        }
        return fallback;
    }
}

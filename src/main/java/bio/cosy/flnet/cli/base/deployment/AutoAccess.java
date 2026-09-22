package bio.cosy.flnet.cli.base.deployment;

import java.util.Locale;

public enum AutoAccess {
    ALL, NONE, CERTIFIED_APPS;

    public String cliName() {
        return this == CERTIFIED_APPS ? "certified" : name().toLowerCase(Locale.ROOT);
    }

    public static AutoAccess parse(String value, AutoAccess fallback) {
        try {
            return value == null ? fallback : valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}

package bio.cosy.flnet.cli.base.deployment;

import java.util.Locale;

public enum SslSource {
    NONE, PROVIDED, SELF_SIGNED;

    public String cliName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}

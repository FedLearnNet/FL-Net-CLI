package bio.cosy.flnet.cli.support;

import java.util.regex.Pattern;

/** Validators for network settings, usable directly as {@link Prompter.Validator}s. */
public final class Net {

    private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    /** Dotted-quad IPv4, usable in {@code @Pattern}. */
    public static final String IPV4_REGEX = "(" + OCTET + "\\.){3}" + OCTET;
    private static final Pattern IPV4 = Pattern.compile(IPV4_REGEX);

    private Net() {
    }

    public static boolean isPort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            return port >= 1 && port <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * A dotted-quad IPv4 address such as 10.0.0.5. Stricter than {@code Inet4Address.ofLiteral},
     * which also accepts legacy forms like "8250" or "1.2.3".
     */
    public static boolean isIpv4(String value) {
        return IPV4.matcher(value.trim()).matches();
    }

    public static String validatePort(String value) {
        return isPort(value) ? null : "'" + value + "' is not a valid port (1-65535).";
    }

    public static String validateIpv4(String value) {
        return isIpv4(value) ? null : "'" + value + "' is not a valid IPv4 address (e.g. 127.0.0.1 or 0.0.0.0).";
    }
}

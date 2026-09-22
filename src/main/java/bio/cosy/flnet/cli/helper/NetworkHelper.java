package bio.cosy.flnet.cli.helper;

import org.apache.commons.validator.routines.InetAddressValidator;

public final class NetworkHelper {

    private NetworkHelper() {
    }

    public static boolean isPort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            return port >= 1 && port <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static boolean isIpv4(String value) {
        return InetAddressValidator.getInstance().isValidInet4Address(value.trim());
    }

    public static String validatePort(String value) {
        return isPort(value) ? null : "'" + value + "' is not a valid port (1-65535).";
    }

    public static String validateIpv4(String value) {
        return isIpv4(value) ? null : "'" + value + "' is not a valid IPv4 address (e.g. 127.0.0.1 or 0.0.0.0).";
    }
}

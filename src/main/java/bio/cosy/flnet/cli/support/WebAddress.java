package bio.cosy.flnet.cli.support;

import lombok.Value;
import lombok.experimental.Accessors;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * An http(s) address in the form {@code protocol://host[:port]} as entered by the user,
 * e.g. {@code https://federated-learning.example.com} or {@code http://localhost:8080}.
 */
@Value
@Accessors(fluent = true)
public class WebAddress {

    private static final Pattern HOST = Pattern.compile("^[a-zA-Z0-9.-]+$");

    String protocol;
    String host;
    int port;

    /** Parses and validates, throwing {@link IllegalArgumentException} with a user facing message. */
    public static WebAddress parse(String input) {
        String raw = input.trim();
        int schemeEnd = raw.indexOf("://");
        if (schemeEnd < 0) {
            throw new IllegalArgumentException("Include the protocol, e.g. 'https://example.com' or 'http://localhost:8080'.");
        }
        String protocol = raw.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        if (!protocol.equals("http") && !protocol.equals("https")) {
            throw new IllegalArgumentException("Only http:// and https:// are supported.");
        }
        String rest = raw.substring(schemeEnd + 3);
        if (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        String host = rest;
        int port = protocol.equals("https") ? 443 : 80;
        int colon = rest.indexOf(':');
        if (colon >= 0) {
            host = rest.substring(0, colon).trim();
            String portText = rest.substring(colon + 1).trim();
            if (!Net.isPort(portText)) {
                throw new IllegalArgumentException("'" + portText + "' is not a valid port (1-65535).");
            }
            port = Integer.parseInt(portText);
        }
        if (host.isEmpty() || host.length() > 253 || !HOST.matcher(host).matches()
                || host.startsWith("-") || host.startsWith(".") || host.endsWith("-") || host.endsWith(".")
                || host.contains("--") || host.contains("..")) {
            throw new IllegalArgumentException("'" + host + "' is not a valid domain name.");
        }
        return new WebAddress(protocol, host, port);
    }

    /** Validator for {@link Prompter}. */
    public static String validate(String input) {
        try {
            parse(input);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    public boolean isHttps() {
        return protocol.equals("https");
    }

    public boolean isDefaultPort() {
        return port == (isHttps() ? 443 : 80);
    }

    public boolean isIpAddress() {
        return Net.isIpv4(host);
    }

    /** {@code host} or {@code host:port} when the port is not the protocol default. */
    public String hostWithPort() {
        return isDefaultPort() ? host : host + ":" + port;
    }

    @Override
    public String toString() {
        return protocol + "://" + hostWithPort();
    }
}

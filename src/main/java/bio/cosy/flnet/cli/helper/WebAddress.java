package bio.cosy.flnet.cli.helper;

import lombok.Value;
import lombok.experimental.Accessors;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

@Value
@Accessors(fluent = true)
public class WebAddress {

    String protocol;
    String host;
    int port;

    public static WebAddress parse(String input) {
        URI uri;
        try {
            uri = new URI(input.trim()).parseServerAuthority();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid web address: " + e.getReason() + ".", e);
        }
        if (uri.getScheme() == null) {
            throw new IllegalArgumentException("Include the protocol, e.g. 'https://example.com' or 'http://localhost:8080'.");
        }
        String protocol = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!protocol.equals("http") && !protocol.equals("https")) {
            throw new IllegalArgumentException("Only http:// and https:// are supported.");
        }
        String host = uri.getHost();
        if (host == null || host.length() > 253 || host.endsWith(".") || host.contains(":")) {
            throw new IllegalArgumentException("Use a domain name, localhost or an IPv4 address.");
        }
        if (host.matches("[0-9.]+") && !NetworkHelper.isIpv4(host)) {
            throw new IllegalArgumentException("'" + host + "' is not a valid IPv4 address.");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) {
            throw new IllegalArgumentException("Use only protocol://host[:port], without credentials, a path, query or fragment.");
        }
        int port = uri.getPort() == -1 ? (protocol.equals("https") ? 443 : 80) : uri.getPort();
        if (port < 1 || port > 65535 || uri.getRawAuthority().endsWith(":")) {
            throw new IllegalArgumentException("The port must be between 1 and 65535.");
        }
        return new WebAddress(protocol, host, port);
    }

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
        return NetworkHelper.isIpv4(host);
    }

    public String hostWithPort() {
        return isDefaultPort() ? host : host + ":" + port;
    }

    @Override
    public String toString() {
        return protocol + "://" + hostWithPort();
    }
}

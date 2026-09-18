package bio.cosy.flnet.cli.config;

import bio.cosy.flnet.cli.support.WebAddress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** The configured networks ({@code flnet.networks.*}) and the frontend image styled after them. */
@ApplicationScoped
public class Networks {

    /** CLI value for a self-deployed platform that is not configured as network. */
    public static final String CUSTOM = "custom";

    @Inject
    FLNetCliConfig config;

    /** All networks in configured order. */
    public List<FLNetNetwork> all() {
        return config.networks().entrySet().stream()
                .filter(entry -> !CUSTOM.equals(entry.getKey()))
                .map(entry -> new FLNetNetwork(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(FLNetNetwork::getOrder).thenComparing(FLNetNetwork::getKey))
                .toList();
    }

    public Optional<FLNetNetwork> byKey(String key) {
        return all().stream().filter(n -> n.getKey().equals(key)).findFirst();
    }

    /** Matches a platform address (or one of its aliases) back to a configured network. */
    public Optional<FLNetNetwork> byPlatformUrl(String url) {
        return all().stream().filter(n -> n.getAddresses().stream().map(Networks::normalize).anyMatch(normalize(url)::equals)).findFirst();
    }

    /** {@code global-*} frontend served by a platform at {@code platformUrl}. */
    public String platformFrontendImage(String platformUrl, String tag) {
        return image("global-", platformUrl, tag);
    }

    /** {@code local-*} frontend of a client, styled after the network it joins. */
    public String clientFrontendImage(String platformUrl, String tag) {
        return image("local-", platformUrl, tag);
    }

    private String image(String prefix, String platformUrl, String tag) {
        String frontend = byPlatformUrl(platformUrl).map(FLNetNetwork::getFrontend).orElse(config.images().frontend().defaultFrontend());
        String registry = config.images().frontend().registry().replaceAll("/+$", "");
        return registry + "/" + prefix + frontend + ":" + tag;
    }

    /** Compares addresses independent of trailing slashes, case and explicit default ports. */
    private static String normalize(String url) {
        try {
            return WebAddress.parse(url).toString().toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return url.strip().toLowerCase(java.util.Locale.ROOT);
        }
    }
}

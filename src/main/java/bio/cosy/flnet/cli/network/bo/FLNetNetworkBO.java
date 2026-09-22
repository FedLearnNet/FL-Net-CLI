package bio.cosy.flnet.cli.network.bo;

import bio.cosy.flnet.cli.config.FLNetCliConfig;
import bio.cosy.flnet.cli.network.FLNetNetwork;
import bio.cosy.flnet.cli.helper.WebAddress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class FLNetNetworkBO {

    public static final String CUSTOM = "custom";

    @Inject
    FLNetCliConfig config;

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

    public Optional<FLNetNetwork> byPlatformUrl(String url) {
        return all().stream().filter(n -> n.getAddresses().stream().map(FLNetNetworkBO::normalize).anyMatch(normalize(url)::equals)).findFirst();
    }

    public String platformFrontendImage(String platformUrl, String tag) {
        return image("global-", platformUrl, tag);
    }

    public String clientFrontendImage(String platformUrl, String tag) {
        return image("local-", platformUrl, tag);
    }

    private String image(String prefix, String platformUrl, String tag) {
        String frontend = byPlatformUrl(platformUrl).map(FLNetNetwork::getFrontend).orElse(config.images().frontend().defaultFrontend());
        String registry = config.images().frontend().registry().replaceAll("/+$", "");
        return registry + "/" + prefix + frontend + ":" + tag;
    }

    private static String normalize(String url) {
        try {
            return WebAddress.parse(url).toString().toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return url.strip().toLowerCase(java.util.Locale.ROOT);
        }
    }
}

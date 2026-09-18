package bio.cosy.flnet.cli.config;

import lombok.AccessLevel;
import lombok.Getter;

import java.util.List;
import java.util.stream.Stream;

/** A network configured in {@code flnet.networks.<key>}; {@code key} is what users pass to {@code --network}. */
@Getter
public class FLNetNetwork {

    private final String key;
    private final String name;
    private final String platformUrl;
    @Getter(AccessLevel.NONE)
    private final List<String> aliases;
    private final int relayPort;
    private final boolean clientAuth;
    private final String frontend;
    private final int order;

    public FLNetNetwork(String key, FLNetCliConfig.NetworkConfig config) {
        this.key = key;
        this.name = config.name();
        this.platformUrl = config.platformUrl();
        this.aliases = config.aliases().orElse(List.of());
        this.relayPort = config.relayPort();
        this.clientAuth = config.clientAuth();
        this.frontend = config.frontend();
        this.order = config.order();
    }

    /** The platform address and its aliases. */
    public List<String> getAddresses() {
        return Stream.concat(Stream.of(platformUrl), aliases.stream()).toList();
    }
}

package bio.cosy.flnet.cli.base.env;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum PlatformEnv implements EnvVariable {
    IMAGE_TAG("Tag of the FL-Net images, e.g. 'latest' or a release version"),
    DEPLOYED_ON_DOMAIN("Public address of the platform (protocol and domain) that users and clients open"),
    HOSTNAME("Bare domain of the platform; the relay checks that its TLS certificate is valid for it"),
    NGINX_PORT("nginx bind address: 127.0.0.1 = localhost only (behind a reverse proxy), 0.0.0.0 = all interfaces"),
    EXPOSED_RELAY_TCP_PORT("Address and TCP port of the relay that clients connect to for federated learning"),
    MIN_CLIENTS_NEEDED_FOR_LEARNING("Minimum number of clients a federated learning needs (fewer weaken privacy techniques such as SMPC)"),
    REQUIRE_CLIENT_AUTHENTICATION("true = clients must log in with an account of the platform Keycloak");

    private final String description;
}

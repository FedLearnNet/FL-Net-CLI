package bio.cosy.flnet.cli.base.env;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum ClientSecretEnv implements EnvVariable {
    QUARKUS_OIDC_CREDENTIALS_SECRET("OIDC client secret of local-learning-api in the client Keycloak"),
    QUARKUS_KEYCLOAK_ADMIN_CLIENT_CLIENT_SECRET("OIDC client secret used by the Keycloak admin client"),
    FLNET_GLOBAL_AUTH_USERNAME("Platform username used for outbound federated requests"),
    FLNET_GLOBAL_AUTH_PASSWORD("Platform password used for outbound federated requests"),
    QUARKUS_OIDC_CLIENT_GLOBAL_WEBSOCKET_GRANT_OPTIONS_PASSWORD_USERNAME("Platform username for the federation WebSocket grant"),
    QUARKUS_OIDC_CLIENT_GLOBAL_WEBSOCKET_GRANT_OPTIONS_PASSWORD_PASSWORD("Platform password for the federation WebSocket grant"),
    LOCAL_LEARNING_SECRET("OIDC client secret Keycloak registers for local-learning-api");

    private final String description;
}

package bio.cosy.flnet.cli.base.env;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum CommonSecretEnv implements EnvVariable {
    POSTGRES_PASSWORD("Password of the database in the secret file's service"),
    QUARKUS_DATASOURCE_PASSWORD("Password of the database in the secret file's service (Quarkus datasource)"),
    KC_BOOTSTRAP_ADMIN_USERNAME("Bootstrap admin username of the deployment's Keycloak"),
    KC_BOOTSTRAP_ADMIN_PASSWORD("Bootstrap admin password of the deployment's Keycloak"),
    KC_DB_PASSWORD("Password of the deployment's Keycloak database");

    private final String description;
}

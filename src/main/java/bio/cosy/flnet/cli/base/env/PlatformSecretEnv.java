package bio.cosy.flnet.cli.base.env;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum PlatformSecretEnv implements EnvVariable {
    QUARKUS_NEO4J_AUTHENTICATION_PASSWORD("Password of the datamodeler-api Neo4j database (Quarkus)"),
    NEO4J_AUTH("Neo4j 'user/password' bootstrap credential"),
    QUARKUS_OIDC_CREDENTIALS_SECRET("OIDC client secret of the file's service in the platform Keycloak"),
    QUARKUS_KEYCLOAK_ADMIN_CLIENT_CLIENT_SECRET("OIDC client secret used by the Keycloak admin client"),
    DATABASE_API_SECRET("OIDC client secret Keycloak registers for global-learning-api"),
    DATAMODELER_API_SECRET("OIDC client secret Keycloak registers for datamodeler-api"),
    PIPELINE_DOCKER_USERNAME("User the build pipeline pushes tool images to the platform registry with"),
    PIPELINE_DOCKER_PASSWORD("Password the build pipeline pushes tool images to the platform registry with"),
    REGISTRY_PUSH_BASIC_AUTH("Basic auth value (base64 of user:password) nginx requires for pushes to the tool registry");

    private final String description;
}

package bio.cosy.flnet.cli.base.deployment;

import bio.cosy.flnet.cli.base.env.CommonEnv;
import bio.cosy.flnet.cli.base.env.CommonSecretEnv;
import bio.cosy.flnet.cli.base.env.EnvVariable;
import bio.cosy.flnet.cli.base.env.PlatformEnv;
import bio.cosy.flnet.cli.base.env.PlatformSecretEnv;
import bio.cosy.flnet.cli.helper.WebAddress;
import bio.cosy.flnet.cli.platform.config.PersistentPlatformConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Delegate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class FLNetPlatformDeployment extends BaseFLNetDeployableInstance {

    public static final String DATAMODELER_SECRETS = "datamodeler-secrets.env";
    public static final String GLOBAL_LEARNING_SECRETS = "global-learning-secrets.env";

    @Valid
    @Delegate
    private final PersistentPlatformConfig config = new PersistentPlatformConfig();

    @Override
    public DeploymentKind getKind() {
        return DeploymentKind.PLATFORM;
    }

    @Override
    public List<Integer> getPorts() {
        return List.of(getNginxPort(), getRelayPort());
    }

    @Override
    public String getAddress() {
        return getDomain() == null ? "?" : getDomain().toString();
    }


    @AssertTrue(message = "A certificate and private key are required: the relay uses TLS with a CA-signed certificate.")
    boolean isCertificateSet() {
        return getSslCertificate() != null && getSslPrivateKey() != null;
    }

    @AssertTrue(message = "nginx is exposed beyond localhost, so it must terminate SSL itself.")
    boolean isSslWhenExposed() {
        return isSslEnabled() || isBehindReverseProxy();
    }


    @Override
    public void generateSecrets(int length, int adminPasswordLength) {
        super.generateSecrets(length, adminPasswordLength);
        config.generateSecrets(length);
    }

    @Override
    public boolean hasSecrets() {
        return super.hasSecrets() && config.hasSecrets();
    }

    @Override
    public Map<String, Map<String, String>> toSecretFiles() {
        Map<String, Map<String, String>> files = new LinkedHashMap<>();
        files.put(DATAMODELER_SECRETS, ordered(
                PlatformSecretEnv.QUARKUS_NEO4J_AUTHENTICATION_PASSWORD.key(), getNeo4jPassword(),
                PlatformSecretEnv.NEO4J_AUTH.key(), "neo4j/" + getNeo4jPassword(),
                PlatformSecretEnv.QUARKUS_OIDC_CREDENTIALS_SECRET.key(), getDatamodelerClientSecret(),
                PlatformSecretEnv.QUARKUS_KEYCLOAK_ADMIN_CLIENT_CLIENT_SECRET.key(), getDatamodelerClientSecret()));
        files.put(GLOBAL_LEARNING_SECRETS, ordered(
                PlatformSecretEnv.QUARKUS_OIDC_CREDENTIALS_SECRET.key(), getGlobalLearningClientSecret(),
                PlatformSecretEnv.QUARKUS_KEYCLOAK_ADMIN_CLIENT_CLIENT_SECRET.key(), getGlobalLearningClientSecret(),
                CommonSecretEnv.QUARKUS_DATASOURCE_PASSWORD.key(), getGlobalLearningDbPassword(),
                CommonSecretEnv.POSTGRES_PASSWORD.key(), getGlobalLearningDbPassword()));
        files.put(ORCH_SECRETS, ordered(
                CommonSecretEnv.QUARKUS_DATASOURCE_PASSWORD.key(), getOrchDbPassword(),
                CommonSecretEnv.POSTGRES_PASSWORD.key(), getOrchDbPassword()));
        files.put(KEYCLOAK_SECRETS, ordered(
                CommonSecretEnv.KC_BOOTSTRAP_ADMIN_USERNAME.key(), getKeycloakAdminUsername(),
                CommonSecretEnv.KC_BOOTSTRAP_ADMIN_PASSWORD.key(), getKeycloakAdminPassword(),
                CommonSecretEnv.KC_DB_PASSWORD.key(), getKeycloakDbPassword(),
                CommonSecretEnv.POSTGRES_PASSWORD.key(), getKeycloakDbPassword(),
                // cross references: Keycloak registers the API clients with their secrets
                PlatformSecretEnv.DATABASE_API_SECRET.key(), getGlobalLearningClientSecret(),
                PlatformSecretEnv.DATAMODELER_API_SECRET.key(), getDatamodelerClientSecret()));
        return files;
    }

    @Override
    public void fromSecretFiles(Map<String, Map<String, String>> files) {
        super.fromSecretFiles(files);
        setNeo4jPassword(secretOrNull(files, DATAMODELER_SECRETS, PlatformSecretEnv.QUARKUS_NEO4J_AUTHENTICATION_PASSWORD.key()));
        setDatamodelerClientSecret(secretOrNull(files, DATAMODELER_SECRETS, PlatformSecretEnv.QUARKUS_OIDC_CREDENTIALS_SECRET.key()));
        setGlobalLearningClientSecret(secretOrNull(files, GLOBAL_LEARNING_SECRETS, PlatformSecretEnv.QUARKUS_OIDC_CREDENTIALS_SECRET.key()));
        setGlobalLearningDbPassword(secretOrNull(files, GLOBAL_LEARNING_SECRETS, CommonSecretEnv.POSTGRES_PASSWORD.key()));
    }


    @Override
    public List<EnvVariable> getEnvVariables() {
        return envVariables(PlatformEnv.values());
    }

    @Override
    public Map<String, Object> toEnv() {
        Map<String, Object> env = new LinkedHashMap<>();
        put(env, PlatformEnv.IMAGE_TAG, getImageTag());
        put(env, CommonEnv.FRONTEND_IMAGE, getFrontendImage());
        put(env, PlatformEnv.DEPLOYED_ON_DOMAIN, getDomain());
        put(env, PlatformEnv.HOSTNAME, getDomain().host());
        put(env, PlatformEnv.NGINX_PORT, getBindIp() + ":" + getNginxPort());
        put(env, PlatformEnv.EXPOSED_RELAY_TCP_PORT, "0.0.0.0:" + getRelayPort());
        put(env, PlatformEnv.MIN_CLIENTS_NEEDED_FOR_LEARNING, getMinClients());
        putSslEnv(env, null);
        put(env, PlatformEnv.REQUIRE_CLIENT_AUTHENTICATION, isClientAuth());
        putCommonEnv(env);
        return env;
    }

    @Override
    public void fromEnv(Map<String, String> env) {
        readCommonEnv(env);
        setImageTag(PlatformEnv.IMAGE_TAG.in(env, getImageTag()));
        try {
            if (PlatformEnv.DEPLOYED_ON_DOMAIN.in(env) != null) {
                setDomain(WebAddress.parse(PlatformEnv.DEPLOYED_ON_DOMAIN.in(env)));
            }
        } catch (IllegalArgumentException _) {
            // hand edited .env; init asks again
        }
        setBindIp(hostOf(PlatformEnv.NGINX_PORT.in(env), getBindIp()));
        setNginxPort(portOf(PlatformEnv.NGINX_PORT.in(env), getNginxPort()));
        setRelayPort(portOf(PlatformEnv.EXPOSED_RELAY_TCP_PORT.in(env), getRelayPort()));
        setMinClients(intOr(PlatformEnv.MIN_CLIENTS_NEEDED_FOR_LEARNING.in(env), getMinClients()));
        setClientAuth(bool(PlatformEnv.REQUIRE_CLIENT_AUTHENTICATION.in(env), isClientAuth()));
    }
}

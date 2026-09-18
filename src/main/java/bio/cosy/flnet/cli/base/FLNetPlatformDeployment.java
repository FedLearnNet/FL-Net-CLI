package bio.cosy.flnet.cli.base;

import bio.cosy.flnet.cli.support.EnvFile;
import bio.cosy.flnet.cli.support.Net;
import bio.cosy.flnet.cli.support.WebAddress;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A self-deployed FL-Net Platform: the global part of a network that clients connect to. Holds the
 * public domain, nginx and relay ports, learning rules and all generated secrets.
 */
@Getter
@Setter
public class FLNetPlatformDeployment extends BaseFLNetDeployableInstance {

    public static final String DATAMODELER_SECRETS = "datamodeler-secrets.env";
    public static final String GLOBAL_LEARNING_SECRETS = "global-learning-secrets.env";
    public static final String ORCH_SECRETS = "orch-secrets.env";
    public static final String KEYCLOAK_SECRETS = "keycloak-secrets.env";

    /** Fewer participants weaken or disable privacy techniques such as SMPC. */
    public static final int RECOMMENDED_MIN_CLIENTS = 3;

    @NotNull(message = "The domain is required.")
    private WebAddress domain;
    @NotNull(message = "The bind IP is required.")
    @Pattern(regexp = Net.IPV4_REGEX, message = "The bind IP must be an IPv4 address.")
    private String bindIp;
    private int nginxPort;
    private int relayPort;
    @Min(value = 1, message = "At least one client is required to start a learning.")
    private int minClients;
    private boolean clientAuth = true;

    // generated secrets
    private String neo4jPassword;
    private String datamodelerClientSecret;
    private String globalLearningClientSecret;
    private String globalLearningDbPassword;
    private String orchDbPassword;
    private String keycloakDbPassword;

    @Override
    public DeploymentKind getKind() {
        return DeploymentKind.PLATFORM;
    }

    @Override
    public List<Integer> getPorts() {
        return List.of(nginxPort, relayPort);
    }

    @Override
    public String getAddress() {
        return domain == null ? "?" : domain.toString();
    }

    /** A platform on localhost is only reachable from this machine and binds to 127.0.0.1. */
    public boolean isLocalOnly() {
        return domain != null && "localhost".equals(domain.host());
    }

    /** nginx only listens locally, so a reverse proxy has to forward the domain to it. */
    public boolean isBehindReverseProxy() {
        return "127.0.0.1".equals(bindIp);
    }

    public boolean isBelowRecommendedMinClients() {
        return minClients < RECOMMENDED_MIN_CLIENTS;
    }

    // ---------------------------------------------------------------- validation

    @AssertTrue(message = "A certificate and private key are required: the relay uses TLS with a CA-signed certificate.")
    boolean isCertificateSet() {
        return getSslCertificate() != null && getSslPrivateKey() != null;
    }

    @AssertTrue(message = "nginx is exposed beyond localhost, so it must terminate SSL itself.")
    boolean isSslWhenExposed() {
        return isSslEnabled() || isBehindReverseProxy();
    }

    // ---------------------------------------------------------------- secrets

    @Override
    public void generateSecrets(int length, int adminPasswordLength) {
        neo4jPassword = EnvFile.secret(length);
        datamodelerClientSecret = EnvFile.secret(length);
        globalLearningClientSecret = EnvFile.secret(length);
        globalLearningDbPassword = EnvFile.secret(length);
        orchDbPassword = EnvFile.secret(length);
        keycloakDbPassword = EnvFile.secret(length);
        // typed by the admin on first login (and should be changed then), so shorter
        setKeycloakAdminPassword(EnvFile.secret(adminPasswordLength));
    }

    @Override
    public boolean hasSecrets() {
        return neo4jPassword != null && datamodelerClientSecret != null && globalLearningClientSecret != null
                && globalLearningDbPassword != null && orchDbPassword != null && keycloakDbPassword != null
                && getKeycloakAdminPassword() != null;
    }

    @Override
    public Map<String, Map<String, String>> toSecretFiles() {
        Map<String, Map<String, String>> files = new LinkedHashMap<>();
        files.put(DATAMODELER_SECRETS, ordered(
                "QUARKUS_NEO4J_AUTHENTICATION_PASSWORD", neo4jPassword,
                "NEO4J_AUTH", "neo4j/" + neo4jPassword,
                "QUARKUS_OIDC_CREDENTIALS_SECRET", datamodelerClientSecret,
                "QUARKUS_KEYCLOAK_ADMIN_CLIENT_CLIENT_SECRET", datamodelerClientSecret));
        files.put(GLOBAL_LEARNING_SECRETS, ordered(
                "QUARKUS_OIDC_CREDENTIALS_SECRET", globalLearningClientSecret,
                "QUARKUS_KEYCLOAK_ADMIN_CLIENT_CLIENT_SECRET", globalLearningClientSecret,
                "QUARKUS_DATASOURCE_PASSWORD", globalLearningDbPassword,
                "POSTGRES_PASSWORD", globalLearningDbPassword));
        files.put(ORCH_SECRETS, ordered(
                "QUARKUS_DATASOURCE_PASSWORD", orchDbPassword,
                "POSTGRES_PASSWORD", orchDbPassword));
        files.put(KEYCLOAK_SECRETS, ordered(
                "KC_BOOTSTRAP_ADMIN_USERNAME", getKeycloakAdminUsername(),
                "KC_BOOTSTRAP_ADMIN_PASSWORD", getKeycloakAdminPassword(),
                "KC_DB_PASSWORD", keycloakDbPassword,
                "POSTGRES_PASSWORD", keycloakDbPassword,
                // cross references: Keycloak registers the API clients with their secrets
                "DATABASE_API_SECRET", globalLearningClientSecret,
                "DATAMODELER_API_SECRET", datamodelerClientSecret));
        return files;
    }

    @Override
    public void fromSecretFiles(Map<String, Map<String, String>> files) {
        neo4jPassword = secretOrNull(files, DATAMODELER_SECRETS, "QUARKUS_NEO4J_AUTHENTICATION_PASSWORD");
        datamodelerClientSecret = secretOrNull(files, DATAMODELER_SECRETS, "QUARKUS_OIDC_CREDENTIALS_SECRET");
        globalLearningClientSecret = secretOrNull(files, GLOBAL_LEARNING_SECRETS, "QUARKUS_OIDC_CREDENTIALS_SECRET");
        globalLearningDbPassword = secretOrNull(files, GLOBAL_LEARNING_SECRETS, "POSTGRES_PASSWORD");
        orchDbPassword = secretOrNull(files, ORCH_SECRETS, "POSTGRES_PASSWORD");
        keycloakDbPassword = secretOrNull(files, KEYCLOAK_SECRETS, "KC_DB_PASSWORD");
        setKeycloakAdminPassword(secretOrNull(files, KEYCLOAK_SECRETS, "KC_BOOTSTRAP_ADMIN_PASSWORD"));
        String admin = secretOrNull(files, KEYCLOAK_SECRETS, "KC_BOOTSTRAP_ADMIN_USERNAME");
        if (admin != null) {
            setKeycloakAdminUsername(admin);
        }
    }

    // ---------------------------------------------------------------- .env

    @Override
    public List<EnvVariable> getEnvVariables() {
        return envVariables(PlatformEnv.values());
    }

    @Override
    public Map<String, Object> toEnv() {
        Map<String, Object> env = new LinkedHashMap<>();
        put(env, PlatformEnv.IMAGE_TAG, getImageTag());
        put(env, CommonEnv.FRONTEND_IMAGE, getFrontendImage());
        put(env, PlatformEnv.DEPLOYED_ON_DOMAIN, domain);
        put(env, PlatformEnv.HOSTNAME, domain.host());
        put(env, PlatformEnv.NGINX_PORT, bindIp + ":" + nginxPort);
        put(env, PlatformEnv.EXPOSED_RELAY_TCP_PORT, "0.0.0.0:" + relayPort);
        put(env, PlatformEnv.MIN_CLIENTS_NEEDED_FOR_LEARNING, minClients);
        putSslEnv(env, null);
        put(env, PlatformEnv.REQUIRE_CLIENT_AUTHENTICATION, clientAuth);
        putCommonEnv(env);
        return env;
    }

    @Override
    public void fromEnv(Map<String, String> env) {
        readCommonEnv(env);
        setImageTag(PlatformEnv.IMAGE_TAG.in(env, getImageTag()));
        try {
            if (PlatformEnv.DEPLOYED_ON_DOMAIN.in(env) != null) {
                domain = WebAddress.parse(PlatformEnv.DEPLOYED_ON_DOMAIN.in(env));
            }
        } catch (IllegalArgumentException _) {
            // hand edited .env; init asks again
        }
        bindIp = hostOf(PlatformEnv.NGINX_PORT.in(env), bindIp);
        nginxPort = portOf(PlatformEnv.NGINX_PORT.in(env), nginxPort);
        relayPort = portOf(PlatformEnv.EXPOSED_RELAY_TCP_PORT.in(env), relayPort);
        minClients = intOr(PlatformEnv.MIN_CLIENTS_NEEDED_FOR_LEARNING.in(env), minClients);
        clientAuth = bool(PlatformEnv.REQUIRE_CLIENT_AUTHENTICATION.in(env), clientAuth);
    }

    /** Accepts 'localhost' as 127.0.0.1. */
    public void setBindIp(String bindIp) {
        this.bindIp = "localhost".equals(bindIp) ? "127.0.0.1" : bindIp;
    }
}

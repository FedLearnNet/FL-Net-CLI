package bio.cosy.flnet.cli.base;

import bio.cosy.flnet.cli.support.EnvFile;
import bio.cosy.flnet.cli.support.Net;
import bio.cosy.flnet.cli.support.Port;
import bio.cosy.flnet.cli.support.WebAddress;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A FL-Net Client: a site that joins a network with its data. Holds the network connection,
 * platform login, data access permissions, web access and all generated secrets.
 */
@Getter
@Setter
public class FLNetClientDeployment extends BaseFLNetDeployableInstance {

    /** Where the client terminates SSL. */
    public enum SslSource {
        NONE, PROVIDED, SELF_SIGNED;

        /** CLI spelling: none, provided, self-signed. */
        public String cliName() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /** Automatic access a default cohort permission grants. */
    public enum AutoAccess {
        ALL, NONE, CERTIFIED_APPS;

        /** CLI spelling: all, none, certified. */
        public String cliName() {
            return this == CERTIFIED_APPS ? "certified" : name().toLowerCase(Locale.ROOT);
        }

        static AutoAccess parse(String value, AutoAccess fallback) {
            try {
                return value == null ? fallback : valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    public static final String ORCH_SECRETS = "orch-secrets.env";
    public static final String LEARNING_SECRETS = "local-learning-secrets.env";
    public static final String KEYCLOAK_SECRETS = "keycloak-secrets.env";
    /** Placeholder login when the platform does not use client authentication. */
    public static final String NO_AUTH_CREDENTIAL = "dummy";
    /** Placeholder certificate path when SSL is off (the compose file always mounts one). */
    private static final String NO_CERTIFICATE = "dummyfile";
    /** Resolves nowhere, so relay and websocket stay off without federation. */
    private static final String NO_FEDERATION_HOST = "federated-learning.invalid";

    // network
    private String networkKey;
    @NotNull(message = "The platform address is required.")
    private WebAddress platformAddress;
    @Port(message = "The platform relay port must be between 1 and 65535.")
    private int platformRelayPort;
    private boolean platformAuth = true;
    private boolean federation = true;
    private String keycloakRealmPath;

    // platform login (outbound credential of local-learning-api, not baked into a volume)
    private String platformUsername;
    private String platformPassword;

    // data access permissions
    private boolean allowAutoStatistics;
    private boolean allowAutoLearning;
    private boolean allowAutoMetrics;
    private boolean defaultPermission;
    private String permissionUser = "";
    private AutoAccess autoStatistics = AutoAccess.NONE;
    private AutoAccess autoMetrics = AutoAccess.NONE;
    private AutoAccess autoLearning = AutoAccess.NONE;
    private boolean allowQueries = true;
    @Min(value = 0, message = "The query retry time cannot be negative.")
    private int queryRetryTime;
    @Min(value = 0, message = "The query sample threshold cannot be negative.")
    private int querySampleThreshold;

    // web access
    @Pattern(regexp = "localhost|" + Net.IPV4_REGEX, message = "The listen address must be 'localhost' or an IPv4 address.")
    private String listen = "localhost";
    private int port;
    private WebAddress domain;
    @Setter(AccessLevel.NONE)
    private SslSource sslSource = SslSource.NONE;

    // generated secrets
    private String orchDbPassword;
    private String learningDbPassword;
    private String learningClientSecret;
    private String keycloakDbPassword;

    @Override
    public DeploymentKind getKind() {
        return DeploymentKind.CLIENT;
    }

    @Override
    public List<Integer> getPorts() {
        return List.of(port);
    }

    @Override
    public String getAddress() {
        return getDeployedOnAddress();
    }

    // ---------------------------------------------------------------- derived values

    /** Docker cannot bind to 'localhost'. */
    public String getBindIp() {
        return "localhost".equals(listen) ? "127.0.0.1" : listen;
    }

    /** nginx server_name and Django ALLOWED_HOSTS: the bare domain, or the listen address. */
    public String getServerName() {
        return domain != null ? domain.host() : listen;
    }

    public String getDeployedOnAddress() {
        return domain != null ? domain.toString() : "http://" + listen + (port == 80 ? "" : ":" + port);
    }

    public String getPlatformKeycloakUrl() {
        return federation && platformAddress != null ? platformAddress + keycloakRealmPath : "";
    }

    public boolean isCustomNetwork() {
        return networkKey == null || "custom".equals(networkKey);
    }

    public Path getSelfSignedDirectory() {
        return resolve("self_signed_certs");
    }

    public Path getSelfSignedCertificate() {
        return getSelfSignedDirectory().resolve("fullchain.pem");
    }

    public Path getSelfSignedPrivateKey() {
        return getSelfSignedDirectory().resolve("privkey.pem");
    }

    public Path getNginxConf() {
        return resolve("nginx.conf");
    }

    /** Sets where SSL is terminated and the matching certificate files. */
    public void useSsl(SslSource source, Path certificate, Path privateKey) {
        sslSource = source;
        setSslEnabled(source != SslSource.NONE);
        switch (source) {
            case NONE -> {
                setSslCertificate(null);
                setSslPrivateKey(null);
            }
            case SELF_SIGNED -> {
                setSslCertificate(getSelfSignedCertificate());
                setSslPrivateKey(getSelfSignedPrivateKey());
            }
            case PROVIDED -> {
                setSslCertificate(certificate);
                setSslPrivateKey(privateKey);
            }
        }
    }

    /** Resets the default cohort permission to "no automatic access, answer queries". */
    public void disableDefaultPermission(int retryTime, int sampleThreshold) {
        defaultPermission = false;
        permissionUser = "";
        autoStatistics = AutoAccess.NONE;
        autoMetrics = AutoAccess.NONE;
        autoLearning = AutoAccess.NONE;
        allowQueries = true;
        queryRetryTime = retryTime;
        querySampleThreshold = sampleThreshold;
    }

    /** Whether a real platform login (not the placeholder) is stored. */
    public boolean hasPlatformLogin() {
        return platformUsername != null && !platformUsername.isBlank() && !NO_AUTH_CREDENTIAL.equals(platformUsername);
    }

    // ---------------------------------------------------------------- validation

    @AssertTrue(message = "The platform requires authentication, so a platform username and password are required.")
    boolean isPlatformLoginComplete() {
        return !platformAuth || (hasPlatformLogin() && platformPassword != null && !platformPassword.isBlank());
    }

    @AssertTrue(message = "SSL termination in the client requires a domain.")
    boolean isDomainSetForSsl() {
        return sslSource == SslSource.NONE || domain != null;
    }

    @AssertTrue(message = "Automatic access in the default permission requires allowing automatic permissions for that resource.")
    boolean isAutoAccessAllowed() {
        return (allowAutoStatistics || autoStatistics == AutoAccess.NONE)
                && (allowAutoMetrics || autoMetrics == AutoAccess.NONE)
                && (allowAutoLearning || autoLearning == AutoAccess.NONE);
    }

    // ---------------------------------------------------------------- secrets

    @Override
    public void generateSecrets(int length, int adminPasswordLength) {
        orchDbPassword = EnvFile.secret(length);
        learningDbPassword = EnvFile.secret(length);
        learningClientSecret = EnvFile.secret(length);
        keycloakDbPassword = EnvFile.secret(length);
        // typed by the admin on first login (and should be changed then), so shorter
        setKeycloakAdminPassword(EnvFile.secret(adminPasswordLength));
    }

    @Override
    public boolean hasSecrets() {
        return orchDbPassword != null && learningDbPassword != null && learningClientSecret != null
                && keycloakDbPassword != null && getKeycloakAdminPassword() != null;
    }

    /** Forgets all generated secrets (clean start). */
    public void clearSecrets() {
        orchDbPassword = null;
        learningDbPassword = null;
        learningClientSecret = null;
        keycloakDbPassword = null;
        setKeycloakAdminPassword(null);
    }

    @Override
    public Map<String, Map<String, String>> toSecretFiles() {
        String username = platformAuth && hasPlatformLogin() ? platformUsername : NO_AUTH_CREDENTIAL;
        String password = platformAuth && platformPassword != null ? platformPassword : NO_AUTH_CREDENTIAL;
        Map<String, Map<String, String>> files = new LinkedHashMap<>();
        files.put(ORCH_SECRETS, ordered(
                "POSTGRES_PASSWORD", orchDbPassword,
                "QUARKUS_DATASOURCE_PASSWORD", orchDbPassword));
        files.put(LEARNING_SECRETS, ordered(
                "POSTGRES_PASSWORD", learningDbPassword,
                "QUARKUS_DATASOURCE_PASSWORD", learningDbPassword,
                "QUARKUS_OIDC_CREDENTIALS_SECRET", learningClientSecret,
                "QUARKUS_KEYCLOAK_ADMIN_CLIENT_CLIENT_SECRET", learningClientSecret,
                "FLNET_GLOBAL_AUTH_USERNAME", username,
                "FLNET_GLOBAL_AUTH_PASSWORD", password,
                "QUARKUS_OIDC_CLIENT_GLOBAL_WEBSOCKET_GRANT_OPTIONS_PASSWORD_USERNAME", username,
                "QUARKUS_OIDC_CLIENT_GLOBAL_WEBSOCKET_GRANT_OPTIONS_PASSWORD_PASSWORD", password));
        files.put(KEYCLOAK_SECRETS, ordered(
                "KC_BOOTSTRAP_ADMIN_USERNAME", getKeycloakAdminUsername(),
                "POSTGRES_PASSWORD", keycloakDbPassword,
                "KC_DB_PASSWORD", keycloakDbPassword,
                "KC_BOOTSTRAP_ADMIN_PASSWORD", getKeycloakAdminPassword(),
                "LOCAL_LEARNING_SECRET", learningClientSecret));
        return files;
    }

    @Override
    public void fromSecretFiles(Map<String, Map<String, String>> files) {
        orchDbPassword = secretOrNull(files, ORCH_SECRETS, "POSTGRES_PASSWORD");
        learningDbPassword = secretOrNull(files, LEARNING_SECRETS, "POSTGRES_PASSWORD");
        learningClientSecret = secretOrNull(files, LEARNING_SECRETS, "QUARKUS_OIDC_CREDENTIALS_SECRET");
        String keycloakDb = secretOrNull(files, KEYCLOAK_SECRETS, "KC_DB_PASSWORD");
        keycloakDbPassword = keycloakDb != null ? keycloakDb : secretOrNull(files, KEYCLOAK_SECRETS, "POSTGRES_PASSWORD");
        setKeycloakAdminPassword(secretOrNull(files, KEYCLOAK_SECRETS, "KC_BOOTSTRAP_ADMIN_PASSWORD"));
        String admin = secretOrNull(files, KEYCLOAK_SECRETS, "KC_BOOTSTRAP_ADMIN_USERNAME");
        if (admin != null) {
            setKeycloakAdminUsername(admin);
        }
        String username = secretOrNull(files, LEARNING_SECRETS, "FLNET_GLOBAL_AUTH_USERNAME");
        platformUsername = NO_AUTH_CREDENTIAL.equals(username) ? null : username;
        String password = secretOrNull(files, LEARNING_SECRETS, "FLNET_GLOBAL_AUTH_PASSWORD");
        platformPassword = NO_AUTH_CREDENTIAL.equals(password) ? null : password;
    }

    // ---------------------------------------------------------------- .env

    @Override
    public List<EnvVariable> getEnvVariables() {
        return envVariables(ClientEnv.values());
    }

    @Override
    public Map<String, Object> toEnv() {
        Map<String, Object> env = new LinkedHashMap<>();
        putCommonEnv(env);
        put(env, ClientEnv.EXPOSED_IP_ADDRESS, getBindIp());
        put(env, ClientEnv.EXPOSED_PORT, port);
        put(env, ClientEnv.DEPLOYED_ON_ADDRESS, getDeployedOnAddress());
        put(env, ClientEnv.DEPLOYED_ON_DOMAIN, getServerName());
        put(env, ClientEnv.HAS_CUSTOM_DOMAIN, domain != null);
        put(env, ClientEnv.GLOBAL_DOMAIN, platformAddress.hostWithPort());
        put(env, ClientEnv.GLOBAL_HTTP_PROTOCOL, platformAddress.protocol());
        put(env, ClientEnv.GLOBAL_WS_PROTOCOL, platformAddress.isHttps() ? "wss" : "ws");
        put(env, ClientEnv.GLOBAL_TCP_PORT, platformRelayPort);
        put(env, ClientEnv.FEDERATED_LEARNING_ENABLED, federation);
        put(env, ClientEnv.GLOBAL_FEDERATION_HOST, federation ? platformAddress.host() : NO_FEDERATION_HOST);
        putSslEnv(env, NO_CERTIFICATE);
        put(env, CommonEnv.FRONTEND_IMAGE, getFrontendImage());
        put(env, ClientEnv.DISABLE_AUTOMATIC_COHORT_PERMISSION_METRICS, !allowAutoMetrics);
        put(env, ClientEnv.DISABLE_AUTOMATIC_COHORT_PERMISSION_STATISTICS, !allowAutoStatistics);
        put(env, ClientEnv.DISABLE_AUTOMATIC_COHORT_PERMISSION_LEARNING, !allowAutoLearning);
        put(env, ClientEnv.COHORT_PERMISSION_ENABLED, defaultPermission);
        put(env, ClientEnv.COHORT_PERMISSION_QUERY_RETRY_TIME, queryRetryTime);
        put(env, ClientEnv.COHORT_PERMISSION_IS_ALLOWED_TO_QUERY, allowQueries);
        put(env, ClientEnv.COHORT_PERMISSION_QUERY_SAMPLE_THRESHOLD, querySampleThreshold);
        put(env, ClientEnv.COHORT_PERMISSION_GLOBAL_USER_ID, permissionUser);
        put(env, ClientEnv.COHORT_PERMISSION_AUTO_TRAINING_ACCESS, autoLearning);
        put(env, ClientEnv.COHORT_PERMISSION_AUTO_STATISTICS_ACCESS, autoStatistics);
        put(env, ClientEnv.COHORT_PERMISSION_AUTO_METRICS_ACCESS, autoMetrics);
        put(env, ClientEnv.GLOBAL_KEYCLOAK_URL, getPlatformKeycloakUrl());
        put(env, ClientEnv.GLOBAL_KEYCLOAK_ENABLED, platformAuth);
        return env;
    }

    @Override
    public void fromEnv(Map<String, String> env) {
        readCommonEnv(env);
        String bindIp = ClientEnv.EXPOSED_IP_ADDRESS.in(env);
        listen = bindIp == null || "127.0.0.1".equals(bindIp) ? "localhost" : bindIp;
        port = intOr(ClientEnv.EXPOSED_PORT.in(env), port);
        domain = bool(ClientEnv.HAS_CUSTOM_DOMAIN.in(env), false) ? parseOrNull(ClientEnv.DEPLOYED_ON_ADDRESS.in(env)) : null;
        if (ClientEnv.GLOBAL_DOMAIN.in(env) != null) {
            platformAddress = parseOrNull(ClientEnv.GLOBAL_HTTP_PROTOCOL.in(env, "https") + "://" + ClientEnv.GLOBAL_DOMAIN.in(env));
        }
        platformRelayPort = intOr(ClientEnv.GLOBAL_TCP_PORT.in(env), platformRelayPort);
        federation = bool(ClientEnv.FEDERATED_LEARNING_ENABLED.in(env), federation);
        platformAuth = bool(ClientEnv.GLOBAL_KEYCLOAK_ENABLED.in(env), platformAuth);
        if (!isSslEnabled()) {
            sslSource = SslSource.NONE;
        } else if (getSslCertificate() != null && getSslCertificate().equals(getSelfSignedCertificate())) {
            sslSource = SslSource.SELF_SIGNED;
        } else {
            sslSource = SslSource.PROVIDED;
        }
        allowAutoMetrics = !bool(ClientEnv.DISABLE_AUTOMATIC_COHORT_PERMISSION_METRICS.in(env), true);
        allowAutoStatistics = !bool(ClientEnv.DISABLE_AUTOMATIC_COHORT_PERMISSION_STATISTICS.in(env), true);
        allowAutoLearning = !bool(ClientEnv.DISABLE_AUTOMATIC_COHORT_PERMISSION_LEARNING.in(env), true);
        defaultPermission = bool(ClientEnv.COHORT_PERMISSION_ENABLED.in(env), false);
        queryRetryTime = intOr(ClientEnv.COHORT_PERMISSION_QUERY_RETRY_TIME.in(env), queryRetryTime);
        allowQueries = bool(ClientEnv.COHORT_PERMISSION_IS_ALLOWED_TO_QUERY.in(env), true);
        querySampleThreshold = intOr(ClientEnv.COHORT_PERMISSION_QUERY_SAMPLE_THRESHOLD.in(env), querySampleThreshold);
        permissionUser = ClientEnv.COHORT_PERMISSION_GLOBAL_USER_ID.in(env, "");
        autoLearning = AutoAccess.parse(ClientEnv.COHORT_PERMISSION_AUTO_TRAINING_ACCESS.in(env), AutoAccess.NONE);
        autoStatistics = AutoAccess.parse(ClientEnv.COHORT_PERMISSION_AUTO_STATISTICS_ACCESS.in(env), AutoAccess.NONE);
        autoMetrics = AutoAccess.parse(ClientEnv.COHORT_PERMISSION_AUTO_METRICS_ACCESS.in(env), AutoAccess.NONE);
    }

    private static WebAddress parseOrNull(String value) {
        try {
            return value == null ? null : WebAddress.parse(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void setPermissionUser(String permissionUser) {
        this.permissionUser = permissionUser == null ? "" : permissionUser;
    }

    /** 'localhost' or an IPv4; 127.0.0.1 is stored as 'localhost'. */
    public void setListen(String listen) {
        this.listen = "127.0.0.1".equals(listen) ? "localhost" : listen;
    }
}

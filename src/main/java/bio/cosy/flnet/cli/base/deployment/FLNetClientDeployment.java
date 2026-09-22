package bio.cosy.flnet.cli.base.deployment;

import bio.cosy.flnet.cli.base.env.ClientEnv;
import bio.cosy.flnet.cli.base.env.ClientSecretEnv;
import bio.cosy.flnet.cli.base.env.CommonEnv;
import bio.cosy.flnet.cli.base.env.CommonSecretEnv;
import bio.cosy.flnet.cli.base.env.EnvVariable;
import bio.cosy.flnet.cli.client.config.PersistentClientConfig;
import bio.cosy.flnet.cli.helper.WebAddress;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Delegate;
import org.hibernate.validator.constraints.IpAddress;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class FLNetClientDeployment extends BaseFLNetDeployableInstance {

    public static final String LEARNING_SECRETS = "local-learning-secrets.env";
    private static final String NO_CERTIFICATE = "dummyfile";
    private static final String NO_FEDERATION_HOST = "federated-learning.invalid";

    // network
    private String networkKey;
    @Valid
    @Delegate
    private final PersistentClientConfig config = new PersistentClientConfig();

    // web access
    private String listen = "localhost";
    private int port;
    private WebAddress domain;
    @Setter(AccessLevel.NONE)
    private SslSource sslSource = SslSource.NONE;

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

    @IpAddress(type = IpAddress.Type.IPv4, message = "The listen address must be 'localhost' or an IPv4 address.")
    public String getBindIp() {
        return "localhost".equals(listen) ? "127.0.0.1" : listen;
    }

    public String getServerName() {
        return domain != null ? domain.host() : listen;
    }

    public String getDeployedOnAddress() {
        return domain != null ? domain.toString() : "http://" + listen + (port == 80 ? "" : ":" + port);
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

    // ---------------------------------------------------------------- validation

    @AssertTrue(message = "SSL termination in the client requires a domain.")
    boolean isDomainSetForSsl() {
        return sslSource == SslSource.NONE || domain != null;
    }

    // ---------------------------------------------------------------- secrets

    @Override
    public void generateSecrets(int length, int adminPasswordLength) {
        super.generateSecrets(length, adminPasswordLength);
        config.generateSecrets(length);
    }

    @Override
    public boolean hasSecrets() {
        return super.hasSecrets() && config.hasSecrets();
    }

    public void clearSecrets() {
        setOrchDbPassword(null);
        config.clearSecrets();
        setKeycloakDbPassword(null);
        setKeycloakAdminPassword(null);
    }

    @Override
    public Map<String, Map<String, String>> toSecretFiles() {
        String username = isPlatformAuth() && hasPlatformLogin() ? getPlatformUsername() : PersistentClientConfig.NO_AUTH_CREDENTIAL;
        String password = isPlatformAuth() && getPlatformPassword() != null ? getPlatformPassword() : PersistentClientConfig.NO_AUTH_CREDENTIAL;
        Map<String, Map<String, String>> files = new LinkedHashMap<>();
        files.put(ORCH_SECRETS, ordered(
                CommonSecretEnv.POSTGRES_PASSWORD.key(), getOrchDbPassword(),
                CommonSecretEnv.QUARKUS_DATASOURCE_PASSWORD.key(), getOrchDbPassword()));
        files.put(LEARNING_SECRETS, ordered(
                CommonSecretEnv.POSTGRES_PASSWORD.key(), getLearningDbPassword(),
                CommonSecretEnv.QUARKUS_DATASOURCE_PASSWORD.key(), getLearningDbPassword(),
                ClientSecretEnv.QUARKUS_OIDC_CREDENTIALS_SECRET.key(), getLearningClientSecret(),
                ClientSecretEnv.QUARKUS_KEYCLOAK_ADMIN_CLIENT_CLIENT_SECRET.key(), getLearningClientSecret(),
                ClientSecretEnv.FLNET_GLOBAL_AUTH_USERNAME.key(), username,
                ClientSecretEnv.FLNET_GLOBAL_AUTH_PASSWORD.key(), password,
                ClientSecretEnv.QUARKUS_OIDC_CLIENT_GLOBAL_WEBSOCKET_GRANT_OPTIONS_PASSWORD_USERNAME.key(), username,
                ClientSecretEnv.QUARKUS_OIDC_CLIENT_GLOBAL_WEBSOCKET_GRANT_OPTIONS_PASSWORD_PASSWORD.key(), password));
        files.put(KEYCLOAK_SECRETS, ordered(
                CommonSecretEnv.KC_BOOTSTRAP_ADMIN_USERNAME.key(), getKeycloakAdminUsername(),
                CommonSecretEnv.POSTGRES_PASSWORD.key(), getKeycloakDbPassword(),
                CommonSecretEnv.KC_DB_PASSWORD.key(), getKeycloakDbPassword(),
                CommonSecretEnv.KC_BOOTSTRAP_ADMIN_PASSWORD.key(), getKeycloakAdminPassword(),
                ClientSecretEnv.LOCAL_LEARNING_SECRET.key(), getLearningClientSecret()));
        return files;
    }

    @Override
    public void fromSecretFiles(Map<String, Map<String, String>> files) {
        super.fromSecretFiles(files);
        setLearningDbPassword(secretOrNull(files, LEARNING_SECRETS, CommonSecretEnv.POSTGRES_PASSWORD.key()));
        setLearningClientSecret(secretOrNull(files, LEARNING_SECRETS, ClientSecretEnv.QUARKUS_OIDC_CREDENTIALS_SECRET.key()));
        if (getKeycloakDbPassword() == null) {
            setKeycloakDbPassword(secretOrNull(files, KEYCLOAK_SECRETS, CommonSecretEnv.POSTGRES_PASSWORD.key()));
        }
        String username = secretOrNull(files, LEARNING_SECRETS, ClientSecretEnv.FLNET_GLOBAL_AUTH_USERNAME.key());
        setPlatformUsername(PersistentClientConfig.NO_AUTH_CREDENTIAL.equals(username) ? null : username);
        String password = secretOrNull(files, LEARNING_SECRETS, ClientSecretEnv.FLNET_GLOBAL_AUTH_PASSWORD.key());
        setPlatformPassword(PersistentClientConfig.NO_AUTH_CREDENTIAL.equals(password) ? null : password);
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
        put(env, ClientEnv.GLOBAL_DOMAIN, getPlatformAddress().hostWithPort());
        put(env, ClientEnv.GLOBAL_HTTP_PROTOCOL, getPlatformAddress().protocol());
        put(env, ClientEnv.GLOBAL_WS_PROTOCOL, getPlatformAddress().isHttps() ? "wss" : "ws");
        put(env, ClientEnv.GLOBAL_TCP_PORT, getPlatformRelayPort());
        put(env, ClientEnv.FEDERATED_LEARNING_ENABLED, isFederation());
        put(env, ClientEnv.GLOBAL_FEDERATION_HOST, isFederation() ? getPlatformAddress().host() : NO_FEDERATION_HOST);
        putSslEnv(env, NO_CERTIFICATE);
        put(env, CommonEnv.FRONTEND_IMAGE, getFrontendImage());
        put(env, ClientEnv.DISABLE_AUTOMATIC_COHORT_PERMISSION_METRICS, !isAllowAutoMetrics());
        put(env, ClientEnv.DISABLE_AUTOMATIC_COHORT_PERMISSION_STATISTICS, !isAllowAutoStatistics());
        put(env, ClientEnv.DISABLE_AUTOMATIC_COHORT_PERMISSION_LEARNING, !isAllowAutoLearning());
        put(env, ClientEnv.COHORT_PERMISSION_ENABLED, isDefaultPermission());
        put(env, ClientEnv.COHORT_PERMISSION_QUERY_RETRY_TIME, getQueryRetryTime());
        put(env, ClientEnv.COHORT_PERMISSION_IS_ALLOWED_TO_QUERY, isAllowQueries());
        put(env, ClientEnv.COHORT_PERMISSION_QUERY_SAMPLE_THRESHOLD, getQuerySampleThreshold());
        put(env, ClientEnv.COHORT_PERMISSION_GLOBAL_USER_ID, getPermissionUser());
        put(env, ClientEnv.COHORT_PERMISSION_AUTO_TRAINING_ACCESS, getAutoLearning());
        put(env, ClientEnv.COHORT_PERMISSION_AUTO_STATISTICS_ACCESS, getAutoStatistics());
        put(env, ClientEnv.COHORT_PERMISSION_AUTO_METRICS_ACCESS, getAutoMetrics());
        put(env, ClientEnv.GLOBAL_KEYCLOAK_URL, getPlatformKeycloakUrl());
        put(env, ClientEnv.GLOBAL_KEYCLOAK_ENABLED, isPlatformAuth());
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
            setPlatformAddress(parseOrNull(ClientEnv.GLOBAL_HTTP_PROTOCOL.in(env, "https") + "://" + ClientEnv.GLOBAL_DOMAIN.in(env)));
        }
        setPlatformRelayPort(intOr(ClientEnv.GLOBAL_TCP_PORT.in(env), getPlatformRelayPort()));
        setFederation(bool(ClientEnv.FEDERATED_LEARNING_ENABLED.in(env), isFederation()));
        setPlatformAuth(bool(ClientEnv.GLOBAL_KEYCLOAK_ENABLED.in(env), isPlatformAuth()));
        if (!isSslEnabled()) {
            sslSource = SslSource.NONE;
        } else if (getSslCertificate() != null && getSslCertificate().equals(getSelfSignedCertificate())) {
            sslSource = SslSource.SELF_SIGNED;
        } else {
            sslSource = SslSource.PROVIDED;
        }
        setAllowAutoMetrics(!bool(ClientEnv.DISABLE_AUTOMATIC_COHORT_PERMISSION_METRICS.in(env), true));
        setAllowAutoStatistics(!bool(ClientEnv.DISABLE_AUTOMATIC_COHORT_PERMISSION_STATISTICS.in(env), true));
        setAllowAutoLearning(!bool(ClientEnv.DISABLE_AUTOMATIC_COHORT_PERMISSION_LEARNING.in(env), true));
        setDefaultPermission(bool(ClientEnv.COHORT_PERMISSION_ENABLED.in(env), false));
        setQueryRetryTime(intOr(ClientEnv.COHORT_PERMISSION_QUERY_RETRY_TIME.in(env), getQueryRetryTime()));
        setAllowQueries(bool(ClientEnv.COHORT_PERMISSION_IS_ALLOWED_TO_QUERY.in(env), true));
        setQuerySampleThreshold(intOr(ClientEnv.COHORT_PERMISSION_QUERY_SAMPLE_THRESHOLD.in(env), getQuerySampleThreshold()));
        setPermissionUser(ClientEnv.COHORT_PERMISSION_GLOBAL_USER_ID.in(env, ""));
        setAutoLearning(AutoAccess.parse(ClientEnv.COHORT_PERMISSION_AUTO_TRAINING_ACCESS.in(env), AutoAccess.NONE));
        setAutoStatistics(AutoAccess.parse(ClientEnv.COHORT_PERMISSION_AUTO_STATISTICS_ACCESS.in(env), AutoAccess.NONE));
        setAutoMetrics(AutoAccess.parse(ClientEnv.COHORT_PERMISSION_AUTO_METRICS_ACCESS.in(env), AutoAccess.NONE));
    }

    private static WebAddress parseOrNull(String value) {
        try {
            return value == null ? null : WebAddress.parse(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void setListen(String listen) {
        this.listen = "127.0.0.1".equals(listen) ? "localhost" : listen;
    }
}

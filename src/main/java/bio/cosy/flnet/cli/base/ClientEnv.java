package bio.cosy.flnet.cli.base;

/** {@code .env} variables of a {@link FLNetClientDeployment} (besides {@link CommonEnv}). */
public enum ClientEnv implements EnvVariable {
    EXPOSED_IP_ADDRESS("IP address nginx binds to: 127.0.0.1 = this machine only, 0.0.0.0 = all interfaces"),
    EXPOSED_PORT("Host port of the web interface"),
    DEPLOYED_ON_ADDRESS("Address users open (protocol, host and port). WARNING: changing DEPLOYED_ON_ADDRESS/DEPLOYED_ON_DOMAIN "
            + "here does NOT update the nginx server_name. Re-run 'flnet client init' instead."),
    DEPLOYED_ON_DOMAIN("Host name nginx serves (server_name, allowed hosts): the domain, or the listen address"),
    HAS_CUSTOM_DOMAIN("true = the client is reached via its own domain instead of the listen address"),
    GLOBAL_DOMAIN("Host (and port) of the platform this client joins"),
    GLOBAL_HTTP_PROTOCOL("Protocol of the platform: http or https"),
    GLOBAL_WS_PROTOCOL("Websocket protocol of the platform: ws or wss"),
    GLOBAL_TCP_PORT("TCP port of the platform relay"),
    FEDERATED_LEARNING_ENABLED("true = take part in federated learning via the platform"),
    GLOBAL_FEDERATION_HOST("Host of the platform relay; 'federated-learning.invalid' turns relay and websocket off"),
    DISABLE_AUTOMATIC_COHORT_PERMISSION_METRICS("true = permissions can never grant automatic access to metrics"),
    DISABLE_AUTOMATIC_COHORT_PERMISSION_STATISTICS("true = permissions can never grant automatic access to statistics"),
    DISABLE_AUTOMATIC_COHORT_PERMISSION_LEARNING("true = permissions can never grant automatic access to learning"),
    COHORT_PERMISSION_ENABLED("true = create a default permission for new cohorts"),
    COHORT_PERMISSION_QUERY_RETRY_TIME("Default permission: minimum seconds between answering repeated queries (rate limiting)"),
    COHORT_PERMISSION_IS_ALLOWED_TO_QUERY("Default permission: allow federated queries against this client's data"),
    COHORT_PERMISSION_QUERY_SAMPLE_THRESHOLD("Default permission: minimum sample size before a query is answered"),
    COHORT_PERMISSION_GLOBAL_USER_ID("Default permission: FL-Net user it applies to (empty = any user)"),
    COHORT_PERMISSION_AUTO_TRAINING_ACCESS("Default permission: automatic learning access (ALL, NONE, CERTIFIED_APPS)"),
    COHORT_PERMISSION_AUTO_STATISTICS_ACCESS("Default permission: automatic statistics access (ALL, NONE, CERTIFIED_APPS)"),
    COHORT_PERMISSION_AUTO_METRICS_ACCESS("Default permission: automatic metrics access (ALL, NONE, CERTIFIED_APPS)"),
    GLOBAL_KEYCLOAK_URL("Keycloak realm of the platform used to log in (empty without federation)"),
    GLOBAL_KEYCLOAK_ENABLED("true = log in to the platform with the platform credentials in env/");

    private final String description;

    ClientEnv(String description) {
        this.description = description;
    }

    @Override
    public String description() {
        return description;
    }
}

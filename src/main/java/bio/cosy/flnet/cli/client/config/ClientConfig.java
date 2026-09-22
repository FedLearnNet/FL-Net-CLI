package bio.cosy.flnet.cli.client.config;

import bio.cosy.flnet.cli.deploy.config.BaseDeploymentConfig;
import lombok.Getter;
import lombok.Setter;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Path;

@Getter
@Setter
public class ClientConfig extends BaseDeploymentConfig {

    public static final String PASSWORD_ENV = "FLNET_PLATFORM_PASSWORD";

    @Option(names = "--mode", paramLabel = "<mode>",
            description = "For an existing client: 'reconfigure' (keeps secrets and data) or 'clean' (regenerates secrets, requires wiping all data).")
    private String mode;

    @Option(names = "--network", paramLabel = "<name>",
            description = "Network to join: ${bundle:flnet.network-keys}, or 'custom' for any self-deployed platform "
                    + "(configured in flnet.networks.*). Default: ${bundle:flnet.client.default-network}.")
    private String network;

    @Option(names = "--platform-url", paramLabel = "<url>", description = "Custom network: platform address, e.g. https://fl.example.org.")
    private String platformUrl;

    @Option(names = "--platform-relay-port", paramLabel = "<port>", description = "Custom network: TCP port of the platform relay. Default: ${bundle:flnet.client.custom-relay-port}.")
    private Integer platformRelayPort;

    @Option(names = "--platform-auth", negatable = true, description = "Custom network: the platform requires client authentication. Default: true.")
    private Boolean platformAuth;

    @Option(names = "--federation", negatable = true, description = "Take part in federated queries and learning. Default: true.")
    private Boolean federation;

    @Option(names = "--platform-username", paramLabel = "<user>", description = "Your account on the FL-Net Platform.")
    private String platformUsername;

    @Option(names = "--platform-password-file", paramLabel = "<file>",
            description = "File containing the platform password. Alternatively set $" + PASSWORD_ENV + ".")
    private Path platformPasswordFile;

    @Option(names = "--update-credentials", negatable = true, description = "Reconfigure: replace the stored platform login. Default: false.")
    private Boolean updateCredentials;

    @Option(names = "--allow-auto-statistics", negatable = true,
            description = "Allow permissions that grant STATISTICS access without manual approval to exist at all. Default: false.")
    private Boolean allowAutoStatistics;

    @Option(names = "--allow-auto-learning", negatable = true,
            description = "Allow permissions that grant LEARNING result access without manual approval to exist at all. Default: false.")
    private Boolean allowAutoLearning;

    @Option(names = "--allow-auto-metrics", negatable = true,
            description = "Allow permissions that grant METRICS access without manual approval to exist at all. Default: false.")
    private Boolean allowAutoMetrics;

    @Option(names = "--default-permission", negatable = true, description = "Create a default permission for every new cohort. Default: false.")
    private Boolean defaultPermission;

    @Option(names = "--permission-user", paramLabel = "<id>", description = "Default permission: FL-Net user ID it applies to (empty = any user).")
    private String permissionUser;

    @Option(names = "--auto-statistics", paramLabel = "<all|none>", description = "Default permission: automatic STATISTICS access.")
    private String autoStatistics;

    @Option(names = "--auto-metrics", paramLabel = "<all|none>", description = "Default permission: automatic METRICS access.")
    private String autoMetrics;

    @Option(names = "--auto-learning", paramLabel = "<all|certified|none>", description = "Default permission: automatic LEARNING result access.")
    private String autoLearning;

    @Option(names = "--allow-queries", negatable = true, description = "Default permission: answer federated queries. Default: true.")
    private Boolean allowQueries;

    @Option(names = "--query-retry-time", paramLabel = "<seconds>", description = "Default permission: minimum seconds between repeated queries. Default: ${bundle:flnet.client.permission.query-retry-time}.")
    private Integer queryRetryTime;

    @Option(names = "--query-sample-threshold", paramLabel = "<n>", description = "Default permission: minimum sample size to answer a query. Default: ${bundle:flnet.client.permission.query-sample-threshold}.")
    private Integer querySampleThreshold;

    @Option(names = "--listen", paramLabel = "<address>",
            description = "Interface the client listens on: localhost (this machine only) or an IPv4 like 0.0.0.0. Default: localhost.")
    private String listen;

    @Option(names = "--domain", paramLabel = "<url>",
            description = "Domain the client is reached at, e.g. https://flnet.hospital.org. 'none' removes it. Default: no domain.")
    private String domain;

    @Option(names = "--ssl", paramLabel = "<source>",
            description = "SSL termination in the client: none, provided (--ssl-cert/--ssl-key), or self-signed. Requires --domain.")
    private String ssl;

    @Option(names = "--ssl-cert", paramLabel = "<file>", description = "Certificate chain for --ssl provided.")
    private Path sslCert;

    @Option(names = "--ssl-key", paramLabel = "<file>", description = "Private key for --ssl provided.")
    private Path sslKey;

    @Option(names = "--create-certificate", negatable = true,
            description = "--ssl self-signed: create the certificate during init (see 'client certs' for its options). Default: true if openssl is installed.")
    private Boolean createCertificate;

    @Mixin
    private CertificateConfig certificate;

    @Option(names = "--port", paramLabel = "<port>", description = "Port the client listens on. Default: the first free port from ${bundle:flnet.client.port} (or the domain port with SSL).")
    private Integer port;
}

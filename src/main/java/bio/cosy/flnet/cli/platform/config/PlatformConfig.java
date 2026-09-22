package bio.cosy.flnet.cli.platform.config;

import bio.cosy.flnet.cli.deploy.config.BaseDeploymentConfig;
import lombok.Getter;
import lombok.Setter;
import picocli.CommandLine.Option;

import java.nio.file.Path;

@Getter
@Setter
public class PlatformConfig extends BaseDeploymentConfig {

    @Option(names = "--domain", paramLabel = "<url>",
            description = "Public address incl. protocol, e.g. https://fl.example.org. Default: ${bundle:flnet.platform.domain}.")
    private String domain;

    @Option(names = "--bind-ip", paramLabel = "<ip>",
            description = "IP the platform nginx binds to: 127.0.0.1 behind a reverse proxy, 0.0.0.0 to expose directly. Default: ${bundle:flnet.platform.bind-ip}.")
    private String bindIp;

    @Option(names = "--port", paramLabel = "<port>", description = "Port of the platform nginx. Default: the first free port from ${bundle:flnet.platform.port}.")
    private Integer port;

    @Option(names = "--relay-port", paramLabel = "<port>",
            description = "Public TCP port of the relay server (always bound on 0.0.0.0, must be reachable by clients). Default: the first free port from ${bundle:flnet.platform.relay-port}.")
    private Integer relayPort;

    @Option(names = "--ssl-cert", paramLabel = "<file>", description = "CA-signed certificate chain (fullchain.pem). Required for the relay TLS.")
    private Path sslCert;

    @Option(names = "--ssl-key", paramLabel = "<file>", description = "Private key of the certificate (privkey.pem).")
    private Path sslKey;

    @Option(names = "--nginx-ssl", negatable = true,
            description = "Terminate SSL in the platform nginx. Only optional when bound to 127.0.0.1 behind an SSL terminating proxy. Default: true.")
    private Boolean nginxSsl;

    @Option(names = "--min-clients", paramLabel = "<n>",
            description = "Minimum clients required to start a learning (below 3 weakens SMPC privacy). Default: ${bundle:flnet.platform.min-clients}.")
    private Integer minClients;

    @Option(names = "--client-auth", negatable = true,
            description = "Require clients to authenticate against the platform Keycloak. Default: true.")
    private Boolean clientAuth;
}

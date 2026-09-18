package bio.cosy.flnet.cli.platform;

import bio.cosy.flnet.cli.config.FLNetCliConfig;
import bio.cosy.flnet.cli.deploy.Bundle;
import bio.cosy.flnet.cli.deploy.ComposeBO;
import bio.cosy.flnet.cli.deploy.ComposeCommands;
import bio.cosy.flnet.cli.deploy.InstanceOptions;
import bio.cosy.flnet.cli.deploy.PortPlanner;
import bio.cosy.flnet.cli.base.FLNetPlatformDeployment;
import bio.cosy.flnet.cli.support.CliException;
import bio.cosy.flnet.cli.support.InteractionOptions;
import bio.cosy.flnet.cli.support.Net;
import bio.cosy.flnet.cli.support.Prompter;
import bio.cosy.flnet.cli.support.Prompter.Validator;
import bio.cosy.flnet.cli.support.Ui;
import bio.cosy.flnet.cli.support.WebAddress;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Asks for the settings of a self-deployed FL-Net Platform (port of {@code platform_installer.py})
 * and fills a {@link FLNetPlatformDeployment}; {@link FLNetPlatformDeploymentBO} does the work.
 * Existing secrets are never regenerated, because they are baked into the database volumes.
 */
@Command(name = "init",
        description = {
                "Create or reconfigure a FL-Net Platform deployment directory.",
                "Asks for every setting that is not given as a flag. Existing secrets are kept.",
                "Several platforms can run on one machine: give each a --name, ports are chosen to not collide."
        },
        footer = {
                "",
                "Examples:",
                "  flnet platform init",
                "  flnet platform init --no-input --domain https://fl.example.org \\",
                "      --ssl-cert /etc/letsencrypt/live/fl.example.org/fullchain.pem \\",
                "      --ssl-key /etc/letsencrypt/live/fl.example.org/privkey.pem"
        })
public class PlatformInitCommand implements Callable<Integer> {


    @Mixin
    InstanceOptions instanceOptions;

    @Option(names = "--domain", paramLabel = "<url>",
            description = "Public address incl. protocol, e.g. https://fl.example.org. Default: ${bundle:flnet.platform.domain}.")
    String domain;

    @Option(names = "--bind-ip", paramLabel = "<ip>",
            description = "IP the platform nginx binds to: 127.0.0.1 behind a reverse proxy, 0.0.0.0 to expose directly. Default: ${bundle:flnet.platform.bind-ip}.")
    String bindIp;

    @Option(names = "--port", paramLabel = "<port>", description = "Port of the platform nginx. Default: the first free port from ${bundle:flnet.platform.port}.")
    String port;

    @Option(names = "--relay-port", paramLabel = "<port>",
            description = "Public TCP port of the relay server (always bound on 0.0.0.0, must be reachable by clients). Default: the first free port from ${bundle:flnet.platform.relay-port}.")
    String relayPort;

    @Option(names = "--ssl-cert", paramLabel = "<file>", description = "CA-signed certificate chain (fullchain.pem). Required for the relay TLS.")
    Path sslCert;

    @Option(names = "--ssl-key", paramLabel = "<file>", description = "Private key of the certificate (privkey.pem).")
    Path sslKey;

    @Option(names = "--nginx-ssl", negatable = true,
            description = "Terminate SSL in the platform nginx. Only optional when bound to 127.0.0.1 behind an SSL terminating proxy. Default: true.")
    Boolean nginxSsl;

    @Option(names = "--min-clients", paramLabel = "<n>",
            description = "Minimum clients required to start a learning (below 3 weakens SMPC privacy). Default: ${bundle:flnet.platform.min-clients}.")
    Integer minClients;

    @Option(names = "--client-auth", negatable = true,
            description = "Require clients to authenticate against the platform Keycloak. Default: true.")
    Boolean clientAuth;

    @Option(names = "--frontend-image", paramLabel = "<image>", description = "Override the frontend image (derived from the domain by default).")
    String frontendImage;

    @Option(names = "--image-tag", paramLabel = "<tag>",
            description = "Image tag for all FL-Net services. Default: ${bundle:flnet.images.tag}.")
    String imageTag;

    @Option(names = "--compose", paramLabel = "<file>", arity = "0..1",
            description = "Also generate one standalone docker compose file (secrets inlined), by default <dir>/"
                    + "${bundle:flnet.compose.generated-file}. Same as 'flnet platform compose' afterwards.")
    String compose;

    @Option(names = "--refresh-files",
            description = "Overwrite docker-compose.yml, nginx and keycloak files with the versions shipped in this CLI.")
    boolean refreshFiles;

    @Option(names = "--bundle-dir", paramLabel = "<dir>", hidden = true,
            description = "Use deployment files from a local FL-Net-Platform-Deployment/FLNET_platform checkout.")
    Path bundleDir;

    @Mixin
    InteractionOptions interaction;

    @Inject
    Prompter prompter;

    @Inject
    FLNetCliConfig config;

    @Inject
    FLNetPlatformDeploymentBO platformBO;

    @Inject
    ComposeBO composeBO;

    @Override
    public Integer call() {
        prompter.configure(interaction);
        Ui.heading("FL-Net Platform setup");
        FLNetPlatformDeployment platform = platformBO.prepareForInit(instanceOptions, prompter);
        if (imageTag != null) {
            platform.setImageTag(imageTag);
        }
        Ui.info("Instance '" + platform.getName() + "' in " + platform.getDirectory());
        if (platform.isInitialized()) {
            Ui.info("Existing configuration found: previous answers are used as defaults, secrets are kept.");
        }
        PortPlanner ports = platformBO.portPlanner(platform);

        Ui.heading("1. Domain");
        Ui.info("Clients connect to the platform over the network, so a real domain with HTTPS is required for");
        Ui.info("production. Traffic for the domain must reach the platform nginx, directly or via your reverse proxy.");
        platformBO.applyDomain(platform, WebAddress.parse(prompter.text("--domain", domain, "Domain incl. protocol",
                platform.getDomain().toString(), WebAddress::validate)));

        Ui.heading("2. Network");
        if (platform.isLocalOnly()) {
            Ui.info("The domain is localhost, so nginx binds to 127.0.0.1 only.");
        } else {
            Ui.info("Use 127.0.0.1 if a reverse proxy on this machine forwards the traffic, 0.0.0.0 to expose nginx directly.");
            platform.setBindIp(prompter.text("--bind-ip", "localhost".equals(bindIp) ? "127.0.0.1" : bindIp,
                    "IP address the platform nginx binds to", platform.getBindIp(), Net::validateIpv4));
        }
        platform.setNginxPort(askPort(ports, "--port", port, "Port of the platform nginx",
                platform.getNginxPort(), config.platform().port()));
        ports.claim(platform.getNginxPort(), "the platform nginx");
        Ui.info("The relay server (federated learning traffic, TCP with its own encryption) is not routed through nginx.");
        Ui.info("Its port is bound on 0.0.0.0 and MUST be reachable by all clients.");
        platform.setRelayPort(askPort(ports, "--relay-port", relayPort, "TCP port of the relay server",
                platform.getRelayPort(), config.platform().relayPort()));

        Ui.heading("3. SSL certificate");
        Ui.info("The federated communication channel uses TLS with a CA-signed certificate, so it is required");
        Ui.info("even if a reverse proxy terminates HTTPS in front of the platform.");
        platform.setSslCertificate(certificate("--ssl-cert", sslCert, "Path to the certificate chain (fullchain.pem)", platform.getSslCertificate()));
        platform.setSslPrivateKey(certificate("--ssl-key", sslKey, "Path to the private key (privkey.pem)", platform.getSslPrivateKey()));
        if (platform.isBehindReverseProxy()) {
            Ui.info("Answer no if an external reverse proxy terminates SSL and forwards to the platform nginx.");
            platform.setSslEnabled(prompter.confirm("--nginx-ssl", nginxSsl, "Use the certificate in the platform nginx?",
                    platform.isSslEnabled()));
        } else {
            if (Boolean.FALSE.equals(nginxSsl)) {
                throw CliException.usage("--no-nginx-ssl is only supported when nginx binds to 127.0.0.1 behind a reverse proxy.");
            }
            Ui.info("nginx is exposed on " + platform.getBindIp() + ", so it terminates SSL with this certificate.");
            platform.setSslEnabled(true);
        }

        Ui.heading("4. Learning and access");
        platform.setMinClients(prompter.integer("--min-clients", minClients, "Minimum number of clients to start a learning",
                platform.getMinClients(), 1));
        if (platform.isBelowRecommendedMinClients()) {
            Ui.warn("Fewer than " + FLNetPlatformDeployment.RECOMMENDED_MIN_CLIENTS + " clients weakens or disables privacy techniques like SMPC.");
            prompter.requireConfirmation("Continue with a minimum of " + platform.getMinClients() + " clients?");
        }
        Ui.info("With authentication, clients need an account in the platform Keycloak (created by you) and are");
        Ui.info("no longer anonymous, but unknown and potentially malicious clients cannot join. Recommended.");
        platform.setClientAuth(prompter.confirm("--client-auth", clientAuth, "Require clients to authenticate?", platform.isClientAuth()));
        if (frontendImage != null) {
            platform.setFrontendImage(frontendImage);
        }

        Ui.heading("Writing configuration");
        boolean generated = platformBO.ensureSecrets(platform);
        Bundle.InstallResult files = platformBO.save(platform, refreshFiles, bundleDir);
        Ui.success(files.summary());
        Ui.success(generated ? "Secrets: generated in " + platform.getSecretsDirectory() + " (readable by your user only)"
                : "Secrets: kept existing files in " + platform.getSecretsDirectory());
        Ui.success("Configuration written to " + platform.getEnvFile());
        if (compose != null) {
            ComposeCommands.printExport(composeBO, platform, compose, false);
        }

        Ui.heading("Next steps");
        Ui.lines(platformBO.nextSteps(platform));
        return 0;
    }

    /** The current (or configured) port, or the next free one when another deployment uses it. */
    private int askPort(PortPlanner ports, String flag, String given, String question, int current, int base) {
        int suggested = ports.suggest(current, base);
        if (given == null && suggested != current) {
            Ui.info("Port " + current + " is taken on this machine, suggesting " + suggested + ".");
        }
        int chosen = Integer.parseInt(prompter.text(flag, given, question, String.valueOf(suggested), ports::validate));
        ports.warnIfBusy(chosen);
        return chosen;
    }

    private Path certificate(String flag, Path given, String question, Path previous) {
        String value = prompter.text(flag, given == null ? null : given.toString(), question,
                previous == null ? null : previous.toString(), Validator.NOT_EMPTY);
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            Ui.warn("'" + path + "' does not exist (yet). It must exist before 'flnet platform up'.");
        }
        return path;
    }
}

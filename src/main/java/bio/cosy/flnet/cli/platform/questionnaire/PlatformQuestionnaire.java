package bio.cosy.flnet.cli.platform.questionnaire;

import bio.cosy.flnet.cli.base.deployment.FLNetPlatformDeployment;
import bio.cosy.flnet.cli.deploy.PortPlanner;
import bio.cosy.flnet.cli.deploy.questionnaire.BaseQuestionnaire;
import bio.cosy.flnet.cli.platform.bo.FLNetPlatformDeploymentBO;
import bio.cosy.flnet.cli.platform.config.PersistentPlatformConfig;
import bio.cosy.flnet.cli.platform.config.PlatformConfig;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.helper.NetworkHelper;
import bio.cosy.flnet.cli.helper.WebAddress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class PlatformQuestionnaire extends BaseQuestionnaire {

    @Inject
    FLNetPlatformDeploymentBO platformBO;

    public void ask(PlatformConfig given, FLNetPlatformDeployment platform) {
        if (platform.isInitialized()) {
            ConsoleHelper.info("Existing configuration found: previous answers are used as defaults, secrets are kept.");
        }
        domain(given, platform);
        network(given, platform, platformBO.portPlanner(platform));
        certificate(given, platform);
        learning(given, platform);
    }

    private void domain(PlatformConfig given, FLNetPlatformDeployment platform) {
        section("1. Domain",
                "Clients connect to the platform over the network, so a real domain with HTTPS is required for",
                "production. Traffic for the domain must reach the platform nginx, directly or via your reverse proxy.");
        platformBO.applyDomain(platform, WebAddress.parse(prompter.text("--domain", given.getDomain(), "Domain incl. protocol",
                platform.getDomain().toString(), WebAddress::validate)));
    }

    private void network(PlatformConfig given, FLNetPlatformDeployment platform, PortPlanner ports) {
        section("2. Network");
        if (platform.isLocalOnly()) {
            ConsoleHelper.info("The domain is localhost, so nginx binds to 127.0.0.1 only.");
        } else {
            ConsoleHelper.info("Use 127.0.0.1 if a reverse proxy on this machine forwards the traffic, 0.0.0.0 to expose nginx directly.");
            platform.setBindIp(prompter.text("--bind-ip", given.getBindIp(), "IP address the platform nginx binds to",
                    platform.getBindIp(), value -> "localhost".equals(value) ? null : NetworkHelper.validateIpv4(value)));
        }
        platform.setNginxPort(port(ports, "--port", given.getPort(), "Port of the platform nginx",
                platform.getNginxPort(), config.platform().port()));
        ports.claim(platform.getNginxPort(), "the platform nginx");
        ConsoleHelper.info("The relay server (federated learning traffic, TCP with its own encryption) is not routed through nginx.");
        ConsoleHelper.info("Its port is bound on 0.0.0.0 and MUST be reachable by all clients.");
        platform.setRelayPort(port(ports, "--relay-port", given.getRelayPort(), "TCP port of the relay server",
                platform.getRelayPort(), config.platform().relayPort()));
    }

    private void certificate(PlatformConfig given, FLNetPlatformDeployment platform) {
        section("3. SSL certificate",
                "The federated communication channel uses TLS with a CA-signed certificate, so it is required",
                "even if a reverse proxy terminates HTTPS in front of the platform.");
        platform.setSslCertificate(file("--ssl-cert", given.getSslCert(), "Path to the certificate chain (fullchain.pem)",
                platform.getSslCertificate(), false));
        platform.setSslPrivateKey(file("--ssl-key", given.getSslKey(), "Path to the private key (privkey.pem)",
                platform.getSslPrivateKey(), false));
        if (platform.isBehindReverseProxy()) {
            ConsoleHelper.info("Answer no if an external reverse proxy terminates SSL and forwards to the platform nginx.");
            platform.setSslEnabled(prompter.confirm("--nginx-ssl", given.getNginxSsl(), "Use the certificate in the platform nginx?",
                    platform.isSslEnabled()));
            return;
        }
        if (Boolean.FALSE.equals(given.getNginxSsl())) {
            throw CliException.usage("--no-nginx-ssl is only supported when nginx binds to 127.0.0.1 behind a reverse proxy.");
        }
        ConsoleHelper.info("nginx is exposed on " + platform.getBindIp() + ", so it terminates SSL with this certificate.");
        platform.setSslEnabled(true);
    }

    private void learning(PlatformConfig given, FLNetPlatformDeployment platform) {
        section("4. Learning and access");
        platform.setMinClients(prompter.integer("--min-clients", given.getMinClients(), "Minimum number of clients to start a learning",
                platform.getMinClients(), 1));
        if (platform.isBelowRecommendedMinClients()) {
            ConsoleHelper.warn("Fewer than " + PersistentPlatformConfig.RECOMMENDED_MIN_CLIENTS + " clients weakens or disables privacy techniques like SMPC.");
            prompter.requireConfirmation("Continue with a minimum of " + platform.getMinClients() + " clients?");
        }
        ConsoleHelper.info("With authentication, clients need an account in the platform Keycloak (created by you) and are");
        ConsoleHelper.info("no longer anonymous, but unknown and potentially malicious clients cannot join. Recommended.");
        platform.setClientAuth(prompter.confirm("--client-auth", given.getClientAuth(), "Require clients to authenticate?",
                platform.isClientAuth()));
    }
}

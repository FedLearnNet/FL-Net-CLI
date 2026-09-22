package bio.cosy.flnet.cli.client.questionnaire;

import bio.cosy.flnet.cli.base.deployment.FLNetClientDeployment;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.helper.Prompter;
import bio.cosy.flnet.cli.helper.WebAddress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ClientSetupWarnings {

    @Inject
    Prompter prompter;

    public void check(FLNetClientDeployment client) {
        WebAddress domain = client.getDomain();
        boolean localhost = client.getListen().equals("localhost");
        boolean ssl = client.isSslEnabled();
        if (domain != null && !domain.isHttps()) {
            ConsoleHelper.warn("The domain uses HTTP: all traffic including passwords is unencrypted. This is strongly discouraged.");
            prompter.requireConfirmation("Continue without encryption?");
        } else if (!localhost && !ssl) {
            ConsoleHelper.warn("The client is exposed on " + client.getListen() + " without SSL in the client.");
            if (domain != null) {
                prompter.acknowledge("A reverse proxy MUST terminate SSL for " + domain.host() + ", forward to port " + client.getPort()
                        + " and preserve the Host header ($host), otherwise the client will not work.");
            } else {
                ConsoleHelper.warn("Without a domain the client is served via plain HTTP. Only do this in a trusted network.");
            }
        }
        if (domain != null && domain.isHttps() && !ssl && localhost) {
            prompter.acknowledge("HTTPS domain without certificates: an external reverse proxy MUST terminate SSL, forward "
                    + domain + " to localhost:" + client.getPort() + " and preserve the Host header ($host).");
        }
        if (domain != null && localhost && ssl) {
            prompter.acknowledge("SSL certificates for a client that only listens on localhost is unusual; usually the reverse proxy terminates SSL.");
        }
        if (domain != null && domain.port() != client.getPort()) {
            prompter.acknowledge("Port mismatch: " + domain + " receives traffic on port " + domain.port() + " but the client listens on "
                    + client.getPort() + ". Relay it, e.g. with a reverse proxy.");
        }
    }
}

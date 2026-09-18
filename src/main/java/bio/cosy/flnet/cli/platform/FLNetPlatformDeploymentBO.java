package bio.cosy.flnet.cli.platform;

import bio.cosy.flnet.cli.config.Networks;
import bio.cosy.flnet.cli.deploy.BaseFLNetDeploymentBO;
import bio.cosy.flnet.cli.base.DeploymentKind;
import bio.cosy.flnet.cli.base.FLNetPlatformDeployment;
import bio.cosy.flnet.cli.support.CliException;
import bio.cosy.flnet.cli.support.WebAddress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/** Business logic of self-deployed FL-Net Platforms: defaults, secrets, saving and next steps. */
@ApplicationScoped
public class FLNetPlatformDeploymentBO extends BaseFLNetDeploymentBO<FLNetPlatformDeployment> {

    @Inject
    Networks networks;

    @Override
    public DeploymentKind kind() {
        return DeploymentKind.PLATFORM;
    }

    @Override
    protected String baseProjectName() {
        return config.platform().projectName();
    }

    @Override
    protected FLNetPlatformDeployment create() {
        FLNetPlatformDeployment platform = new FLNetPlatformDeployment();
        platform.setImageTag(config.images().tag());
        platform.setKeycloakAdminUsername(config.platform().keycloakAdminUsername());
        platform.setDomain(WebAddress.parse(config.platform().domain()));
        platform.setBindIp(config.platform().bindIp());
        platform.setNginxPort(config.platform().port());
        platform.setRelayPort(config.platform().relayPort());
        platform.setMinClients(config.platform().minClients());
        platform.setSslEnabled(true);
        return platform;
    }

    /** Sets the public domain; the frontend is styled after the network hosted there. */
    public void applyDomain(FLNetPlatformDeployment platform, WebAddress domain) {
        platform.setDomain(domain);
        if (platform.isLocalOnly()) {
            platform.setBindIp("127.0.0.1");
        }
        platform.setFrontendImage(networks.platformFrontendImage(domain.toString(), platform.getImageTag()));
    }

    /**
     * Keeps existing secrets: they are baked into the database and Keycloak volumes on first start.
     * Returns whether new ones were generated.
     */
    public boolean ensureSecrets(FLNetPlatformDeployment platform) {
        if (platform.hasSecrets()) {
            return false;
        }
        if (hasStoredSecrets(platform)) {
            // mixing old and new secrets would break the cross references (e.g. DATABASE_API_SECRET)
            throw new CliException("Only some secret files exist in " + platform.getSecretsDirectory() + ". Restore the missing ones, "
                    + "or delete the directory and all volumes ('flnet platform down --volumes') for a fresh start.", CliException.ENVIRONMENT);
        }
        platform.generateSecrets(config.secrets().length(), config.secrets().adminPasswordLength());
        return true;
    }

    /** What to do after {@code init}; lines starting with two spaces are commands. */
    public List<String> nextSteps(FLNetPlatformDeployment platform) {
        List<String> lines = new ArrayList<>();
        lines.add("Start the platform:");
        lines.add("  flnet platform up" + nameFlag(platform));
        lines.add("Keycloak admin credentials: " + platform.getSecretsDirectory().resolve(FLNetPlatformDeployment.KEYCLOAK_SECRETS)
                + " (change the password after first login).");
        if (platform.isClientAuth()) {
            lines.add("Client authentication is on: create an account in the platform Keycloak for every client.");
        }
        if (platform.isSslEnabled()) {
            lines.add("After renewing the certificate, reload nginx: docker compose exec reverse-proxy-encrypted nginx -s reload");
        }
        if (platform.isBehindReverseProxy()) {
            lines.add("nginx only listens on localhost: configure your reverse proxy to forward the domain to it"
                    + (platform.isSslEnabled() ? " via HTTPS, using the same certificate." : "."));
        }
        lines.add("Guide: " + config.documentationUrl() + "/docs/deployment/self-deployment-guide");
        return lines;
    }
}

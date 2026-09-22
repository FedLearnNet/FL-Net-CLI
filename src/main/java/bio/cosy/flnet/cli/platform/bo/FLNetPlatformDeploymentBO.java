package bio.cosy.flnet.cli.platform.bo;

import bio.cosy.flnet.cli.base.deployment.DeploymentKind;
import bio.cosy.flnet.cli.base.deployment.FLNetPlatformDeployment;
import bio.cosy.flnet.cli.deploy.bo.BaseFLNetDeploymentBO;
import bio.cosy.flnet.cli.network.bo.FLNetNetworkBO;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.WebAddress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class FLNetPlatformDeploymentBO extends BaseFLNetDeploymentBO<FLNetPlatformDeployment> {

    @Inject
    FLNetNetworkBO networks;

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


    public void applyDomain(FLNetPlatformDeployment platform, WebAddress domain) {
        platform.setDomain(domain);
        if (platform.isLocalOnly()) {
            platform.setBindIp("127.0.0.1");
        }
        platform.setFrontendImage(networks.platformFrontendImage(domain.toString(), platform.getImageTag()));
    }

    @Override
    public boolean ensureSecrets(FLNetPlatformDeployment platform) {
        if (!platform.hasSecrets() && hasStoredSecrets(platform)) {
            // mixing old and new secrets would break the cross references (e.g. DATABASE_API_SECRET)
            throw new CliException("Only some secret files exist in " + platform.getSecretsDirectory() + ". Restore the missing ones, "
                    + "or delete the directory and all volumes ('flnet platform clean') for a fresh start.", CliException.ENVIRONMENT);
        }
        return super.ensureSecrets(platform);
    }

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

package bio.cosy.flnet.cli.client.bo;

import bio.cosy.flnet.cli.base.deployment.DeploymentKind;
import bio.cosy.flnet.cli.base.deployment.FLNetClientDeployment;
import bio.cosy.flnet.cli.base.deployment.SslSource;
import bio.cosy.flnet.cli.client.ClientInitMode;
import bio.cosy.flnet.cli.deploy.bo.BaseFLNetDeploymentBO;
import bio.cosy.flnet.cli.network.FLNetNetwork;
import bio.cosy.flnet.cli.network.bo.FLNetNetworkBO;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.helper.WebAddress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class FLNetClientDeploymentBO extends BaseFLNetDeploymentBO<FLNetClientDeployment> {

    private static final Pattern SERVER_NAME = Pattern.compile("^(\\s*server_name\\s+).*?;\\s*$", Pattern.MULTILINE);

    @Inject
    FLNetNetworkBO networks;

    @Override
    public DeploymentKind kind() {
        return DeploymentKind.CLIENT;
    }

    @Override
    protected String baseProjectName() {
        return config.client().projectName();
    }

    @Override
    protected FLNetClientDeployment create() {
        FLNetClientDeployment client = new FLNetClientDeployment();
        client.setImageTag(config.images().tag());
        client.setKeycloakAdminUsername(config.client().keycloakAdminUsername());
        client.setKeycloakRealmPath(config.keycloakRealmPath());
        client.setPort(config.client().port());
        client.setPlatformRelayPort(config.client().customRelayPort());
        client.setQueryRetryTime(config.client().permission().queryRetryTime());
        client.setQuerySampleThreshold(config.client().permission().querySampleThreshold());
        client.setNetworkKey(config.client().defaultNetwork());
        return client;
    }

    @Override
    protected void afterLoad(FLNetClientDeployment client) {
        client.setNetworkKey(client.getPlatformAddress() == null ? FLNetNetworkBO.CUSTOM
                : networks.byPlatformUrl(client.getPlatformAddress().toString()).map(FLNetNetwork::getKey).orElse(FLNetNetworkBO.CUSTOM));
    }

    public boolean isInstalled(FLNetClientDeployment client) {
        return client.isInitialized() && client.getSecretFileNames().stream()
                .allMatch(file -> Files.exists(client.getSecretsDirectory().resolve(file)));
    }

    // ---------------------------------------------------------------- network

    public void applyNetwork(FLNetClientDeployment client, FLNetNetwork network) {
        client.setNetworkKey(network.getKey());
        client.setPlatformAddress(WebAddress.parse(network.getPlatformUrl()));
        client.setPlatformRelayPort(network.getRelayPort());
        client.setPlatformAuth(network.isClientAuth());
        client.setFrontendImage(networks.clientFrontendImage(network.getPlatformUrl(), client.getImageTag()));
    }

    public void applyCustomPlatform(FLNetClientDeployment client, WebAddress platform, int relayPort, boolean auth) {
        client.setNetworkKey(FLNetNetworkBO.CUSTOM);
        client.setPlatformAddress(platform);
        client.setPlatformRelayPort(relayPort);
        client.setPlatformAuth(auth);
        if (networks.byPlatformUrl(platform.toString()).isPresent() || client.getFrontendImage() == null) {
            client.setFrontendImage(networks.clientFrontendImage(platform.toString(), client.getImageTag()));
        }
    }

    // ---------------------------------------------------------------- saving

    @Override
    protected void afterSave(FLNetClientDeployment client) {
        patchNginxServerName(client);
    }

    void patchNginxServerName(FLNetClientDeployment client) {
        Path nginx = client.getNginxConf();
        try {
            String content = Files.readString(nginx, StandardCharsets.UTF_8);
            Matcher matcher = SERVER_NAME.matcher(content);
            if (!matcher.find()) {
                ConsoleHelper.warn("No server_name directive found in " + nginx + "; nginx may not use the correct hostname.");
                return;
            }
            String patched = content.substring(0, matcher.start()) + matcher.group(1) + client.getServerName() + ";"
                    + content.substring(matcher.end());
            Files.writeString(nginx, patched, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot patch " + nginx, e);
        }
    }

    // ---------------------------------------------------------------- next steps

    public List<String> nextSteps(FLNetClientDeployment client, ClientInitMode mode) {
        String flag = nameFlag(client);
        List<String> lines = new ArrayList<>();
        if (client.getSslSource() == SslSource.SELF_SIGNED && !Files.exists(client.getSelfSignedCertificate())) {
            lines.add("Create the self-signed certificate first:");
            lines.add("  flnet client certs" + flag);
            lines.add("Also comment out the HSTS header in nginx_conf_HTTPS.conf (or disable HSTS for the domain in your browser),");
            lines.add("otherwise browsers refuse self-signed certificates.");
        }
        WebAddress domain = client.getDomain();
        if (domain != null && domain.isHttps() && domain.isIpAddress()) {
            lines.add("You use HTTPS with an IP address: nginx cannot select the server block by IP. In nginx.conf remove the");
            lines.add("server block with 'listen 443 ssl default_server;' and in nginx_conf_HTTPS.conf change 'listen 443 ssl;'");
            lines.add("to 'listen 443 ssl default_server;'.");
        }
        switch (mode) {
            case CLEAN -> {
                lines.add("Secrets changed, so the old data is incompatible. Wipe it and start (deletes ALL data):");
                lines.add("  flnet client down --volumes" + flag);
                lines.add("  flnet client up" + flag);
            }
            case RECONFIGURE -> {
                lines.add("Apply the new configuration (data and secrets are preserved, do NOT use 'down --volumes'):");
                lines.add("  flnet client up" + flag);
            }
            case FRESH -> {
                lines.add("Start the client:");
                lines.add("  flnet client up" + flag);
                lines.add("Then open " + client.getDeployedOnAddress() + " and follow the first-login steps:");
                lines.add(config.documentationUrl() + "/docs/deployment/deploy-client");
                lines.add("Keycloak admin credentials: " + client.getSecretsDirectory().resolve(FLNetClientDeployment.KEYCLOAK_SECRETS));
            }
        }
        return lines;
    }

    public Path saveInstructions(FLNetClientDeployment client, List<String> lines) {
        Path file = client.resolve("client_startup_instructions.txt");
        try {
            Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            ConsoleHelper.warn("Could not save the instructions to " + file + ": " + e.getMessage());
            return null;
        }
    }
}

package bio.cosy.flnet.cli.client;

import bio.cosy.flnet.cli.config.FLNetNetwork;
import bio.cosy.flnet.cli.config.Networks;
import bio.cosy.flnet.cli.deploy.BaseFLNetDeploymentBO;
import bio.cosy.flnet.cli.base.DeploymentKind;
import bio.cosy.flnet.cli.base.FLNetClientDeployment;
import bio.cosy.flnet.cli.base.FLNetClientDeployment.SslSource;
import bio.cosy.flnet.cli.support.CliException;
import bio.cosy.flnet.cli.support.FilePermissions;
import bio.cosy.flnet.cli.support.Processes;
import bio.cosy.flnet.cli.support.Ui;
import bio.cosy.flnet.cli.support.WebAddress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Business logic of FL-Net Clients: defaults, networks, saving, certificates and next steps. */
@ApplicationScoped
public class FLNetClientDeploymentBO extends BaseFLNetDeploymentBO<FLNetClientDeployment> {

    /** How {@code init} treats an existing client. */
    public enum Mode {
        /** Nothing installed yet. */
        CLEAN,
        /** New answers, same secrets and data. */
        RECONFIGURE,
        /** New secrets, which requires wiping all data. */
        FORCE_CLEAN
    }

    private static final Pattern SERVER_NAME = Pattern.compile("^(\\s*server_name\\s+).*?;\\s*$", Pattern.MULTILINE);

    @Inject
    Networks networks;

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
        client.setNetworkKey(client.getPlatformAddress() == null ? Networks.CUSTOM
                : networks.byPlatformUrl(client.getPlatformAddress().toString()).map(FLNetNetwork::getKey).orElse(Networks.CUSTOM));
    }

    /** Whether the client is complete: configuration and every secret file. */
    public boolean isInstalled(FLNetClientDeployment client) {
        return client.isInitialized() && client.getSecretFileNames().stream()
                .allMatch(file -> Files.exists(client.getSecretsDirectory().resolve(file)));
    }

    // ---------------------------------------------------------------- network

    /** Joins a configured network: platform, relay, authentication and frontend styling come from it. */
    public void applyNetwork(FLNetClientDeployment client, FLNetNetwork network) {
        client.setNetworkKey(network.getKey());
        client.setPlatformAddress(WebAddress.parse(network.getPlatformUrl()));
        client.setPlatformRelayPort(network.getRelayPort());
        client.setPlatformAuth(network.isClientAuth());
        client.setFrontendImage(networks.clientFrontendImage(network.getPlatformUrl(), client.getImageTag()));
    }

    /** Joins a self-deployed platform; a hand-picked frontend image is kept unless the platform is a known network. */
    public void applyCustomPlatform(FLNetClientDeployment client, WebAddress platform, int relayPort, boolean auth) {
        client.setNetworkKey(Networks.CUSTOM);
        client.setPlatformAddress(platform);
        client.setPlatformRelayPort(relayPort);
        client.setPlatformAuth(auth);
        if (networks.byPlatformUrl(platform.toString()).isPresent() || client.getFrontendImage() == null) {
            client.setFrontendImage(networks.clientFrontendImage(platform.toString(), client.getImageTag()));
        }
    }

    /** Creates the secrets unless they exist; returns whether new ones were generated. */
    public boolean ensureSecrets(FLNetClientDeployment client) {
        if (client.hasSecrets()) {
            return false;
        }
        client.generateSecrets(config.secrets().length(), config.secrets().adminPasswordLength());
        return true;
    }

    // ---------------------------------------------------------------- saving

    @Override
    protected void afterSave(FLNetClientDeployment client) {
        patchNginxServerName(client);
    }

    /** Replaces the first server_name (the main server block precedes the catch-all blocks). */
    void patchNginxServerName(FLNetClientDeployment client) {
        Path nginx = client.getNginxConf();
        try {
            String content = Files.readString(nginx, StandardCharsets.UTF_8);
            Matcher matcher = SERVER_NAME.matcher(content);
            if (!matcher.find()) {
                Ui.warn("No server_name directive found in " + nginx + "; nginx may not use the correct hostname.");
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

    /** What to do after {@code init}; lines starting with two spaces are commands. */
    public List<String> nextSteps(FLNetClientDeployment client, Mode mode) {
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
            case FORCE_CLEAN -> {
                lines.add("Secrets changed, so the old data is incompatible. Wipe it and start (deletes ALL data):");
                lines.add("  flnet client down --volumes" + flag);
                lines.add("  flnet client up" + flag);
            }
            case RECONFIGURE -> {
                lines.add("Apply the new configuration (data and secrets are preserved, do NOT use 'down --volumes'):");
                lines.add("  flnet client up" + flag);
            }
            case CLEAN -> {
                lines.add("Start the client:");
                lines.add("  flnet client up" + flag);
                lines.add("Then open " + client.getDeployedOnAddress() + " and follow the first-login steps:");
                lines.add(config.documentationUrl() + "/docs/deployment/deploy-client");
                lines.add("Keycloak admin credentials: " + client.getSecretsDirectory().resolve(FLNetClientDeployment.KEYCLOAK_SECRETS));
            }
        }
        return lines;
    }

    /** Keeps the next steps next to the deployment; returns the file, or null if it could not be written. */
    public Path saveInstructions(FLNetClientDeployment client, List<String> lines) {
        Path file = client.resolve("client_startup_instructions.txt");
        try {
            Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            Ui.warn("Could not save the instructions to " + file + ": " + e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------- certificates

    /** Version of the openssl on the PATH; fails when it is missing. */
    public String requireOpenssl() {
        return Processes.probe("openssl", "version")
                .orElseThrow(() -> new CliException("openssl is not installed or not on the PATH "
                        + "(e.g. 'sudo apt install openssl' or 'brew install openssl').", CliException.ENVIRONMENT));
    }

    /** san.cnf from the template, then openssl; the key is readable by the nginx container. */
    public void createSelfSignedCertificate(FLNetClientDeployment client, CertificateRequest request) {
        Path template = client.getSelfSignedDirectory().resolve("san.cnf.template");
        if (!Files.isRegularFile(template)) {
            throw new CliException("No client deployment found in " + client.getDirectory() + ". Run 'flnet client init' first.",
                    CliException.ENVIRONMENT);
        }
        if (request.getDnsNames().isEmpty() && request.getIpAddresses().isEmpty()) {
            throw CliException.usage("At least one DNS name (--dns) or IP address (--ip) is required.");
        }
        Path sanCnf = client.getSelfSignedDirectory().resolve("san.cnf");
        writeSanConfig(template, sanCnf, request);
        int code = Processes.runInteractive(null, List.of("openssl", "req", "-x509", "-newkey", "rsa", "-nodes",
                "-keyout", client.getSelfSignedPrivateKey().toString(), "-out", client.getSelfSignedCertificate().toString(),
                "-days", String.valueOf(request.getDays()), "-config", sanCnf.toString()));
        if (code != 0) {
            throw new CliException("openssl failed with exit code " + code + ".", code);
        }
        // the nginx container runs as a different user and must be able to read both files
        FilePermissions.set(client.getSelfSignedPrivateKey(), FilePermissions.READABLE);
        FilePermissions.set(client.getSelfSignedCertificate(), FilePermissions.READABLE);
    }

    static void writeSanConfig(Path template, Path target, CertificateRequest request) {
        try {
            String content = Files.readString(template, StandardCharsets.UTF_8);
            List<String> dn = new ArrayList<>(List.of("[dn]"));
            if (!request.getCountry().isEmpty()) {
                dn.add("C  = " + request.getCountry().toUpperCase(Locale.ROOT));
            }
            addIfPresent(dn, "ST = ", request.getState());
            addIfPresent(dn, "L  = ", request.getCity());
            addIfPresent(dn, "O  = ", request.getOrganization());
            addIfPresent(dn, "OU = ", request.getOrganizationalUnit());
            dn.add("CN = " + request.getCommonName());
            content = content.replaceFirst("(?s)\\[dn\\].*?(?=\\n\\[)", Matcher.quoteReplacement(String.join("\n", dn)));

            List<String> alt = new ArrayList<>(List.of("[alt_names]"));
            for (int i = 0; i < request.getDnsNames().size(); i++) {
                alt.add("DNS." + (i + 1) + " = " + request.getDnsNames().get(i));
            }
            for (int i = 0; i < request.getIpAddresses().size(); i++) {
                alt.add("IP." + (i + 1) + "  = " + request.getIpAddresses().get(i));
            }
            content = content.replaceFirst("(?s)\\[alt_names\\].*$", Matcher.quoteReplacement(String.join("\n", alt) + "\n"));
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + target, e);
        }
    }

    private static void addIfPresent(List<String> lines, String prefix, String value) {
        if (value != null && !value.isEmpty()) {
            lines.add(prefix + value);
        }
    }

}

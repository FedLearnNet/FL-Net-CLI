package bio.cosy.flnet.cli.client;

import bio.cosy.flnet.cli.client.FLNetClientDeploymentBO.Mode;
import bio.cosy.flnet.cli.config.FLNetCliConfig;
import bio.cosy.flnet.cli.config.FLNetNetwork;
import bio.cosy.flnet.cli.config.Networks;
import bio.cosy.flnet.cli.deploy.Bundle;
import bio.cosy.flnet.cli.deploy.ComposeBO;
import bio.cosy.flnet.cli.deploy.ComposeCommands;
import bio.cosy.flnet.cli.deploy.InstanceOptions;
import bio.cosy.flnet.cli.deploy.PortPlanner;
import bio.cosy.flnet.cli.base.FLNetClientDeployment;
import bio.cosy.flnet.cli.base.FLNetClientDeployment.AutoAccess;
import bio.cosy.flnet.cli.base.FLNetClientDeployment.SslSource;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Asks for the settings of a FL-Net Client (port of {@code client_installer.py}) and fills a
 * {@link FLNetClientDeployment}; {@link FLNetClientDeploymentBO} does the work. Runs in one of three
 * modes, decided up front:
 * <ul>
 *     <li>clean: nothing installed yet, generate everything.</li>
 *     <li>reconfigure: re-ask all questions (prefilled with previous answers) but never touch the
 *     generated secrets, which are baked into the Postgres/Keycloak volumes. Applied with
 *     {@code flnet client up}.</li>
 *     <li>force-clean: regenerate all secrets, which requires wiping all volumes and data.</li>
 * </ul>
 */
@Command(name = "init",
        description = {
                "Create or reconfigure a FL-Net Client deployment directory.",
                "Asks for every setting that is not given as a flag. Re-running it reconfigures an existing client.",
                "Several clients can run on one machine: give each a --name, ports are chosen to not collide."
        },
        footer = {
                "",
                "Examples:",
                "  flnet client init",
                "  flnet client init --no-input --network flnet --platform-username alice \\",
                "      --platform-password-file ./password.txt --listen localhost --port 8250",
                "  FLNET_PLATFORM_PASSWORD=... flnet client init --no-input --network custom \\",
                "      --platform-url https://fl.example.org --platform-relay-port 9150 --platform-username site-a"
        })
public class ClientInitCommand implements Callable<Integer> {

    static final String PASSWORD_ENV = "FLNET_PLATFORM_PASSWORD";

    @Mixin
    InstanceOptions instanceOptions;

    @Option(names = "--mode", paramLabel = "<mode>",
            description = "For an existing client: 'reconfigure' (keeps secrets and data) or 'clean' (regenerates secrets, requires wiping all data).")
    String mode;

    // --- network
    @Option(names = "--network", paramLabel = "<name>",
            description = "Network to join: ${bundle:flnet.network-keys}, or 'custom' for any self-deployed platform "
                    + "(configured in flnet.networks.*). Default: ${bundle:flnet.client.default-network}.")
    String network;

    @Option(names = "--platform-url", paramLabel = "<url>", description = "Custom network: platform address, e.g. https://fl.example.org.")
    String platformUrl;

    @Option(names = "--platform-relay-port", paramLabel = "<port>", description = "Custom network: TCP port of the platform relay. Default: ${bundle:flnet.client.custom-relay-port}.")
    String platformRelayPort;

    @Option(names = "--platform-auth", negatable = true, description = "Custom network: the platform requires client authentication. Default: true.")
    Boolean platformAuth;

    @Option(names = "--federation", negatable = true, description = "Take part in federated queries and learning. Default: true.")
    Boolean federation;

    // --- credentials
    @Option(names = "--platform-username", paramLabel = "<user>", description = "Your account on the FL-Net Platform.")
    String platformUsername;

    @Option(names = "--platform-password-file", paramLabel = "<file>",
            description = "File containing the platform password. Alternatively set $" + PASSWORD_ENV + ".")
    Path platformPasswordFile;

    @Option(names = "--update-credentials", negatable = true, description = "Reconfigure: replace the stored platform login. Default: false.")
    Boolean updateCredentials;

    // --- permissions
    @Option(names = "--allow-auto-statistics", negatable = true,
            description = "Allow permissions that grant STATISTICS access without manual approval to exist at all. Default: false.")
    Boolean allowAutoStatistics;

    @Option(names = "--allow-auto-learning", negatable = true,
            description = "Allow permissions that grant LEARNING result access without manual approval to exist at all. Default: false.")
    Boolean allowAutoLearning;

    @Option(names = "--allow-auto-metrics", negatable = true,
            description = "Allow permissions that grant METRICS access without manual approval to exist at all. Default: false.")
    Boolean allowAutoMetrics;

    @Option(names = "--default-permission", negatable = true, description = "Create a default permission for every new cohort. Default: false.")
    Boolean defaultPermission;

    @Option(names = "--permission-user", paramLabel = "<id>", description = "Default permission: FL-Net user ID it applies to (empty = any user).")
    String permissionUser;

    @Option(names = "--auto-statistics", paramLabel = "<all|none>", description = "Default permission: automatic STATISTICS access.")
    String autoStatistics;

    @Option(names = "--auto-metrics", paramLabel = "<all|none>", description = "Default permission: automatic METRICS access.")
    String autoMetrics;

    @Option(names = "--auto-learning", paramLabel = "<all|certified|none>", description = "Default permission: automatic LEARNING result access.")
    String autoLearning;

    @Option(names = "--allow-queries", negatable = true, description = "Default permission: answer federated queries. Default: true.")
    Boolean allowQueries;

    @Option(names = "--query-retry-time", paramLabel = "<seconds>", description = "Default permission: minimum seconds between repeated queries. Default: 3.")
    Integer queryRetryTime;

    @Option(names = "--query-sample-threshold", paramLabel = "<n>", description = "Default permission: minimum sample size to answer a query. Default: 100.")
    Integer querySampleThreshold;

    // --- web access
    @Option(names = "--listen", paramLabel = "<address>",
            description = "Interface the client listens on: localhost (this machine only) or an IPv4 like 0.0.0.0. Default: localhost.")
    String listen;

    @Option(names = "--domain", paramLabel = "<url>",
            description = "Domain the client is reached at, e.g. https://flnet.hospital.org. 'none' removes it. Default: no domain.")
    String domain;

    @Option(names = "--ssl", paramLabel = "<source>",
            description = "SSL termination in the client: none, provided (--ssl-cert/--ssl-key), or self-signed. Requires --domain.")
    String ssl;

    @Option(names = "--ssl-cert", paramLabel = "<file>", description = "Certificate chain for --ssl provided.")
    Path sslCert;

    @Option(names = "--ssl-key", paramLabel = "<file>", description = "Private key for --ssl provided.")
    Path sslKey;

    @Option(names = "--port", paramLabel = "<port>", description = "Port the client listens on. Default: the first free port from ${bundle:flnet.client.port} (or the domain port with SSL).")
    String port;

    @Option(names = "--frontend-image", paramLabel = "<image>", description = "Override the frontend image (derived from the network by default).")
    String frontendImage;

    @Option(names = "--image-tag", paramLabel = "<tag>", description = "Image tag. Default: ${bundle:flnet.images.tag}.")
    String imageTag;

    @Option(names = "--compose", paramLabel = "<file>", arity = "0..1",
            description = "Also generate one standalone docker compose file (secrets inlined), by default <dir>/"
                    + "${bundle:flnet.compose.generated-file}. Same as 'flnet client compose' afterwards.")
    String compose;

    @Option(names = "--refresh-files",
            description = "Overwrite docker-compose.yml, nginx and keycloak files with the versions shipped in this CLI.")
    boolean refreshFiles;

    @Option(names = "--bundle-dir", paramLabel = "<dir>", hidden = true,
            description = "Use deployment files from a local FL-Net-Client-Deployment/FLNet_client checkout.")
    Path bundleDir;

    @Mixin
    InteractionOptions interaction;

    @Inject
    Prompter prompter;

    @Inject
    FLNetCliConfig config;

    @Inject
    Networks networks;

    @Inject
    FLNetClientDeploymentBO clientBO;

    @Inject
    ComposeBO composeBO;

    @Override
    public Integer call() {
        prompter.configure(interaction);
        Ui.heading("FL-Net Client setup");
        FLNetClientDeployment client = clientBO.prepareForInit(instanceOptions, prompter);
        if (imageTag != null) {
            client.setImageTag(imageTag);
        }
        Ui.info("Instance '" + client.getName() + "' in " + client.getDirectory());
        Mode runMode = determineMode(client);
        PortPlanner ports = clientBO.portPlanner(client);

        askNetwork(client);
        askCredentials(client, runMode);
        askPermissions(client);
        askWebAccess(client, ports);
        if (frontendImage != null) {
            client.setFrontendImage(frontendImage);
        }

        Ui.heading("Writing configuration");
        boolean generated = clientBO.ensureSecrets(client);
        Bundle.InstallResult files = clientBO.save(client, refreshFiles, bundleDir);
        Ui.success(files.summary());
        Ui.success(generated ? "Secrets: generated in " + client.getSecretsDirectory() : "Secrets: kept existing values");
        Ui.success("Configuration written to " + client.getEnvFile());
        if (compose != null) {
            ComposeCommands.printExport(composeBO, client, compose, false);
        }

        Ui.heading("Next steps");
        List<String> lines = clientBO.nextSteps(client, runMode);
        Ui.lines(lines);
        Path saved = clientBO.saveInstructions(client, lines);
        if (saved != null) {
            Ui.info("(Saved to " + saved + ")");
        }
        return 0;
    }

    // ------------------------------------------------------------------ mode

    private Mode determineMode(FLNetClientDeployment client) {
        if (!clientBO.isInstalled(client)) {
            if ("reconfigure".equals(mode)) {
                throw CliException.usage("--mode reconfigure: no complete client configuration found in " + client.getDirectory() + ".");
            }
            Ui.info("No existing configuration found, starting a fresh setup.");
            client.clearSecrets();
            return Mode.CLEAN;
        }
        Ui.info("An existing FL-Net Client configuration was found.");
        Mode chosen = prompter.choice("--mode", mode,
                "Reconfigure it (keeps secrets and data) or start clean (regenerates secrets, wipes all data)?",
                List.of(Mode.RECONFIGURE, Mode.FORCE_CLEAN), m -> m == Mode.RECONFIGURE ? "reconfigure" : "clean", Mode.RECONFIGURE);
        if (chosen == Mode.FORCE_CLEAN) {
            Ui.warn("A clean start regenerates ALL secrets. The existing databases no longer match them, so you must run "
                    + "'flnet client down --volumes', which permanently deletes ALL data (users, cohorts, learning results).");
            prompter.requireConfirmation("Start clean and lose all existing data?");
            client.clearSecrets();
        } else {
            if (!client.hasSecrets()) {
                throw new CliException("The existing secret files in " + client.getSecretsDirectory()
                        + " are incomplete. Restore them, or use --mode clean for a fresh start.", CliException.ENVIRONMENT);
            }
            Ui.info("Reconfiguring: previous answers are the defaults, secrets are kept.");
        }
        return chosen;
    }

    // ------------------------------------------------------------------ network

    private void askNetwork(FLNetClientDeployment client) {
        Ui.heading("1. Network");
        Ui.info("The client reads data schemas and the tool registry from a network (read-only; subscribing to a");
        Ui.info("schema only increments its counter). Optionally it also takes part in federated queries and learning.");

        Map<String, FLNetNetwork> options = new LinkedHashMap<>();
        for (FLNetNetwork n : networks.all()) {
            options.put(n.getKey(), n);
        }
        options.put(Networks.CUSTOM, null);
        if (network == null) {
            options.forEach((key, n) -> Ui.info("  " + String.format("%-12s", key)
                    + (n == null ? "a self-deployed platform" : n.getName() + " (" + n.getPlatformUrl() + ")")));
        }
        String defaultKey = options.containsKey(client.getNetworkKey()) ? client.getNetworkKey() : Networks.CUSTOM;
        FLNetNetwork known = prompter.choice("--network", network, "Which network do you join?", options, defaultKey);

        if (known != null) {
            clientBO.applyNetwork(client, known);
            Ui.info("Joining " + known.getName() + " at " + known.getPlatformUrl() + " (relay port " + known.getRelayPort() + ").");
        } else {
            Ui.info("Make sure your self-deployed platform is running before you continue.");
            String previousUrl = client.isCustomNetwork() && client.getPlatformAddress() != null ? client.getPlatformAddress().toString() : null;
            WebAddress platform = WebAddress.parse(prompter.text("--platform-url", platformUrl, "Platform address incl. protocol",
                    previousUrl, WebAddress::validate));
            int relay = Integer.parseInt(prompter.text("--platform-relay-port", platformRelayPort, "TCP relay port of the platform",
                    String.valueOf(client.getPlatformRelayPort()), Net::validatePort));
            boolean auth = prompter.confirm("--platform-auth", platformAuth, "Does the platform require client authentication?",
                    client.isPlatformAuth());
            clientBO.applyCustomPlatform(client, platform, relay, auth);
        }

        Ui.info("Federation uses the TCP relay and a WebSocket to run privacy-preserving queries and learning across sites.");
        client.setFederation(prompter.confirm("--federation", federation, "Take part in federated queries and learning?",
                client.isFederation()));
    }

    // ------------------------------------------------------------------ credentials

    /**
     * The platform login is an outbound credential of local-learning-api, not baked into any local
     * volume, so it may change in every mode.
     */
    private void askCredentials(FLNetClientDeployment client, Mode runMode) {
        if (!client.isPlatformAuth()) {
            return;
        }
        Ui.heading("2. Platform account");
        if (runMode == Mode.RECONFIGURE && client.hasPlatformLogin()
                && !prompter.confirm("--update-credentials", updateCredentials,
                "Replace the stored platform login (" + client.getPlatformUsername() + ")?", false)) {
            return;
        }
        Ui.info("The client authenticates to the platform with your platform account. Create it on the platform first.");
        client.setPlatformUsername(prompter.text("--platform-username", platformUsername, "Platform username", null, Validator.NOT_EMPTY));
        client.setPlatformPassword(prompter.secret("--platform-password-file", platformPasswordFile, PASSWORD_ENV, "Platform password"));
    }

    // ------------------------------------------------------------------ permissions

    private void askPermissions(FLNetClientDeployment client) {
        Ui.heading("3. Data access permissions");
        Ui.info("Permissions control which network users may access federated queries, statistics, learning results and");
        Ui.info("metrics of this client. Statistics, learning results and metrics need manual approval per request by default.");
        Ui.info("First decide whether permissions that skip manual approval may exist at all:");
        client.setAllowAutoStatistics(prompter.confirm("--allow-auto-statistics", allowAutoStatistics,
                "Allow automatic access permissions for STATISTICS?", client.isAllowAutoStatistics()));
        client.setAllowAutoLearning(prompter.confirm("--allow-auto-learning", allowAutoLearning,
                "Allow automatic access permissions for LEARNING results?", client.isAllowAutoLearning()));
        client.setAllowAutoMetrics(prompter.confirm("--allow-auto-metrics", allowAutoMetrics,
                "Allow automatic access permissions for METRICS?", client.isAllowAutoMetrics()));

        Ui.info("A default permission can be created automatically for every new cohort; it also configures federated queries.");
        boolean enabled = prompter.confirm("--default-permission", defaultPermission,
                "Create a default permission for every new cohort?", client.isDefaultPermission());
        if (!enabled) {
            client.disableDefaultPermission(config.client().permission().queryRetryTime(), config.client().permission().querySampleThreshold());
            return;
        }
        client.setDefaultPermission(true);
        client.setPermissionUser(prompter.text("--permission-user", permissionUser,
                "FL-Net user ID the permission is for (empty = any user)", client.getPermissionUser(), Validator.ANY));
        List<AutoAccess> allOrNone = List.of(AutoAccess.ALL, AutoAccess.NONE);
        client.setAutoStatistics(!client.isAllowAutoStatistics() ? AutoAccess.NONE
                : prompter.choice("--auto-statistics", autoStatistics, "Automatic STATISTICS access ('none' = manual approval)",
                allOrNone, AutoAccess::cliName, client.getAutoStatistics()));
        client.setAutoMetrics(!client.isAllowAutoMetrics() ? AutoAccess.NONE
                : prompter.choice("--auto-metrics", autoMetrics, "Automatic METRICS access ('none' = manual approval)",
                allOrNone, AutoAccess::cliName, client.getAutoMetrics()));
        if (client.isAllowAutoLearning()) {
            Ui.info("Tools that need internet or host access always require manual approval, regardless of this setting.");
        }
        client.setAutoLearning(!client.isAllowAutoLearning() ? AutoAccess.NONE
                : prompter.choice("--auto-learning", autoLearning, "Automatic LEARNING result access ('certified' = only certified tools)",
                List.of(AutoAccess.values()), AutoAccess::cliName, client.getAutoLearning()));
        Ui.info("Federated queries are answered automatically when allowed, protected by thresholding and rate limiting.");
        client.setAllowQueries(prompter.confirm("--allow-queries", allowQueries, "Allow federated queries against this client?",
                client.isAllowQueries()));
        if (client.isAllowQueries()) {
            client.setQueryRetryTime(prompter.integer("--query-retry-time", queryRetryTime,
                    "Minimum seconds between repeated queries", client.getQueryRetryTime(), 0));
            client.setQuerySampleThreshold(prompter.integer("--query-sample-threshold", querySampleThreshold,
                    "Minimum sample size before a query is answered", client.getQuerySampleThreshold(), 0));
        }
    }

    // ------------------------------------------------------------------ web access

    private void askWebAccess(FLNetClientDeployment client, PortPlanner ports) {
        Ui.heading("4. Web access");
        Ui.info("The client is used in the browser. Listen on localhost (this machine only) or expose it, e.g. on 0.0.0.0.");
        Ui.info("If you expose it, restrict access to your internal network or a VPN.");
        client.setListen(prompter.text("--listen", listen, "Address to listen on (localhost or IPv4)", client.getListen(),
                value -> value.equals("localhost") || value.equals("127.0.0.1") ? null : Net.validateIpv4(value)));

        Ui.info("Optionally set the domain of the client: it enables strict CORS/host checks and SSL termination.");
        String previousDomain = client.getDomain() == null ? "" : client.getDomain().toString();
        String domainInput = prompter.text("--domain", domain, "Domain incl. protocol (empty or 'none' for no domain)", previousDomain,
                value -> value.isEmpty() || value.equalsIgnoreCase("none") ? null : WebAddress.validate(value));
        client.setDomain(domainInput.isEmpty() || domainInput.equalsIgnoreCase("none") ? null : WebAddress.parse(domainInput));

        if (client.getDomain() != null) {
            SslSource source = prompter.choice("--ssl", ssl, "SSL termination in the client: none (e.g. your reverse proxy does it), "
                    + "provided certificate files, or self-signed", List.of(SslSource.values()), SslSource::cliName, client.getSslSource());
            if (source == SslSource.PROVIDED) {
                boolean previousProvided = client.getSslSource() == SslSource.PROVIDED;
                Path cert = existingFile("--ssl-cert", sslCert, "Certificate chain file (fullchain.pem)",
                        previousProvided ? client.getSslCertificate() : null);
                Path key = existingFile("--ssl-key", sslKey, "Private key file (privkey.pem)",
                        previousProvided ? client.getSslPrivateKey() : null);
                client.useSsl(SslSource.PROVIDED, cert, key);
                prompter.acknowledge("Certificates are NOT renewed or reloaded automatically. After renewing (e.g. certbot deploy hook) run: "
                        + "docker compose exec reverse-proxy-encrypted nginx -s reload");
            } else {
                client.useSsl(source, null, null);
                if (source == SslSource.SELF_SIGNED && !Files.exists(client.getSelfSignedCertificate())) {
                    Ui.info("The certificate does not exist yet. Create it before starting with: flnet client certs");
                }
            }
        } else {
            if (ssl != null && !ssl.equals("none")) {
                throw CliException.usage("--ssl " + ssl + " requires --domain.");
            }
            client.useSsl(SslSource.NONE, null, null);
        }

        int preferred = client.getDomain() != null && client.isSslEnabled() ? client.getDomain().port() : client.getPort();
        String suggested = String.valueOf(ports.suggest(preferred, config.client().port()));
        if (port == null && !suggested.equals(String.valueOf(preferred))) {
            Ui.info("Port " + preferred + " is taken on this machine, suggesting " + suggested + ".");
        }
        client.setPort(Integer.parseInt(prompter.text("--port", port, "Port the client listens on", suggested, ports::validate)));
        ports.warnIfBusy(client.getPort());
        warnAboutMisconfiguration(client);
    }

    private void warnAboutMisconfiguration(FLNetClientDeployment client) {
        WebAddress d = client.getDomain();
        boolean localhost = client.getListen().equals("localhost");
        boolean sslFiles = client.isSslEnabled();
        if (d != null && !d.isHttps()) {
            Ui.warn("The domain uses HTTP: all traffic including passwords is unencrypted. This is strongly discouraged.");
            prompter.requireConfirmation("Continue without encryption?");
        } else if (!localhost && !sslFiles) {
            Ui.warn("The client is exposed on " + client.getListen() + " without SSL in the client.");
            if (d != null) {
                prompter.acknowledge("A reverse proxy MUST terminate SSL for " + d.host() + ", forward to port " + client.getPort()
                        + " and preserve the Host header ($host), otherwise the client will not work.");
            } else {
                Ui.warn("Without a domain the client is served via plain HTTP. Only do this in a trusted network.");
            }
        }
        if (d != null && d.isHttps() && !sslFiles && localhost) {
            prompter.acknowledge("HTTPS domain without certificates: an external reverse proxy MUST terminate SSL, forward "
                    + d + " to localhost:" + client.getPort() + " and preserve the Host header ($host).");
        }
        if (d != null && localhost && sslFiles) {
            prompter.acknowledge("SSL certificates for a client that only listens on localhost is unusual; usually the reverse proxy terminates SSL.");
        }
        if (d != null && d.port() != client.getPort()) {
            prompter.acknowledge("Port mismatch: " + d + " receives traffic on port " + d.port() + " but the client listens on "
                    + client.getPort() + ". Relay it, e.g. with a reverse proxy.");
        }
    }

    private Path existingFile(String flag, Path given, String question, Path def) {
        String value = prompter.text(flag, given == null ? null : given.toString(), question, def == null ? null : def.toString(),
                input -> input.isBlank() ? "A file is required."
                        : Files.isRegularFile(Path.of(input)) ? null : "'" + input + "' does not exist.");
        return Path.of(value).toAbsolutePath().normalize();
    }
}

package bio.cosy.flnet.cli.client.questionnaire;

import bio.cosy.flnet.cli.base.deployment.AutoAccess;
import bio.cosy.flnet.cli.base.deployment.FLNetClientDeployment;
import bio.cosy.flnet.cli.base.deployment.SslSource;
import bio.cosy.flnet.cli.client.ClientInitMode;
import bio.cosy.flnet.cli.client.bo.ClientCertificateBO;
import bio.cosy.flnet.cli.client.bo.FLNetClientDeploymentBO;
import bio.cosy.flnet.cli.client.config.ClientConfig;
import bio.cosy.flnet.cli.deploy.PortPlanner;
import bio.cosy.flnet.cli.deploy.questionnaire.BaseQuestionnaire;
import bio.cosy.flnet.cli.network.FLNetNetwork;
import bio.cosy.flnet.cli.network.bo.FLNetNetworkBO;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.helper.NetworkHelper;
import bio.cosy.flnet.cli.helper.Prompter.Validator;
import bio.cosy.flnet.cli.helper.WebAddress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@ApplicationScoped
public class ClientQuestionnaire extends BaseQuestionnaire {

    @Inject
    FLNetClientDeploymentBO clientBO;

    @Inject
    FLNetNetworkBO networks;

    @Inject
    ClientCertificateBO certificates;

    @Inject
    CertificateQuestionnaire certificateQuestionnaire;

    @Inject
    ClientSetupWarnings warnings;

    public ClientInitMode mode(ClientConfig given, FLNetClientDeployment client) {
        if (!clientBO.isInstalled(client)) {
            if (ClientInitMode.RECONFIGURE.cliName().equals(given.getMode())) {
                throw CliException.usage("--mode reconfigure: no complete client configuration found in " + client.getDirectory() + ".");
            }
            ConsoleHelper.info("No existing configuration found, starting a fresh setup.");
            client.clearSecrets();
            return ClientInitMode.FRESH;
        }
        ConsoleHelper.info("An existing FL-Net Client configuration was found.");
        ClientInitMode mode = prompter.choice("--mode", given.getMode(),
                "Reconfigure it (keeps secrets and data) or start clean (regenerates secrets, wipes all data)?",
                List.of(ClientInitMode.RECONFIGURE, ClientInitMode.CLEAN), ClientInitMode::cliName, ClientInitMode.RECONFIGURE);
        if (mode == ClientInitMode.CLEAN) {
            ConsoleHelper.warn("A clean start regenerates ALL secrets. The existing databases no longer match them, so you must run "
                    + "'flnet client down --volumes', which permanently deletes ALL data (users, cohorts, learning results).");
            prompter.requireConfirmation("Start clean and lose all existing data?");
            client.clearSecrets();
        } else if (!client.hasSecrets()) {
            throw new CliException("The existing secret files in " + client.getSecretsDirectory()
                    + " are incomplete. Restore them, or use --mode clean for a fresh start.", CliException.ENVIRONMENT);
        } else {
            ConsoleHelper.info("Reconfiguring: previous answers are the defaults, secrets are kept.");
        }
        return mode;
    }

    public void ask(ClientConfig given, FLNetClientDeployment client, ClientInitMode mode) {
        network(given, client);
        credentials(given, client, mode);
        permissions(given, client);
        webAccess(given, client, clientBO.portPlanner(client));
        certificate(given, client);
    }


    private void network(ClientConfig given, FLNetClientDeployment client) {
        section("1. Network",
                "The client reads data schemas and the tool registry from a network (read-only; subscribing to a",
                "schema only increments its counter). Optionally it also takes part in federated queries and learning.");
        Map<String, FLNetNetwork> options = new LinkedHashMap<>();
        networks.all().forEach(n -> options.put(n.getKey(), n));
        options.put(FLNetNetworkBO.CUSTOM, null);
        if (given.getNetwork() == null) {
            options.forEach((key, n) -> ConsoleHelper.info("  " + String.format("%-12s", key)
                    + (n == null ? "a self-deployed platform" : n.getName() + " (" + n.getPlatformUrl() + ")")));
        }
        String previous = options.containsKey(client.getNetworkKey()) ? client.getNetworkKey() : FLNetNetworkBO.CUSTOM;
        FLNetNetwork known = prompter.choice("--network", given.getNetwork(), "Which network do you join?", options, previous);
        if (known != null) {
            clientBO.applyNetwork(client, known);
            ConsoleHelper.info("Joining " + known.getName() + " at " + known.getPlatformUrl() + " (relay port " + known.getRelayPort() + ").");
        } else {
            customPlatform(given, client);
        }
        ConsoleHelper.info("Federation uses the TCP relay and a WebSocket to run privacy-preserving queries and learning across sites.");
        client.setFederation(prompter.confirm("--federation", given.getFederation(), "Take part in federated queries and learning?",
                client.isFederation()));
    }

    private void customPlatform(ClientConfig given, FLNetClientDeployment client) {
        ConsoleHelper.info("Make sure your self-deployed platform is running before you continue.");
        String previousUrl = client.isCustomNetwork() ? text(client.getPlatformAddress()) : null;
        WebAddress platform = WebAddress.parse(prompter.text("--platform-url", given.getPlatformUrl(), "Platform address incl. protocol",
                previousUrl, WebAddress::validate));
        int relay = Integer.parseInt(prompter.text("--platform-relay-port", text(given.getPlatformRelayPort()), "TCP relay port of the platform",
                String.valueOf(client.getPlatformRelayPort()), NetworkHelper::validatePort));
        boolean auth = prompter.confirm("--platform-auth", given.getPlatformAuth(), "Does the platform require client authentication?",
                client.isPlatformAuth());
        clientBO.applyCustomPlatform(client, platform, relay, auth);
    }


    private void credentials(ClientConfig given, FLNetClientDeployment client, ClientInitMode mode) {
        if (!client.isPlatformAuth()) {
            return;
        }
        section("2. Platform account");
        if (mode == ClientInitMode.RECONFIGURE && client.hasPlatformLogin() && !prompter.confirm("--update-credentials",
                given.getUpdateCredentials(), "Replace the stored platform login (" + client.getPlatformUsername() + ")?", false)) {
            return;
        }
        ConsoleHelper.info("The client authenticates to the platform with your platform account. Create it on the platform first.");
        client.setPlatformUsername(prompter.text("--platform-username", given.getPlatformUsername(), "Platform username", null,
                Validator.NOT_EMPTY));
        client.setPlatformPassword(prompter.secret("--platform-password-file", given.getPlatformPasswordFile(),
                ClientConfig.PASSWORD_ENV, "Platform password"));
    }


    private void permissions(ClientConfig given, FLNetClientDeployment client) {
        section("3. Data access permissions",
                "Permissions control which network users may access federated queries, statistics, learning results and",
                "metrics of this client. Statistics, learning results and metrics need manual approval per request by default.",
                "First decide whether permissions that skip manual approval may exist at all:");
        client.setAllowAutoStatistics(prompter.confirm("--allow-auto-statistics", given.getAllowAutoStatistics(),
                "Allow automatic access permissions for STATISTICS?", client.isAllowAutoStatistics()));
        client.setAllowAutoLearning(prompter.confirm("--allow-auto-learning", given.getAllowAutoLearning(),
                "Allow automatic access permissions for LEARNING results?", client.isAllowAutoLearning()));
        client.setAllowAutoMetrics(prompter.confirm("--allow-auto-metrics", given.getAllowAutoMetrics(),
                "Allow automatic access permissions for METRICS?", client.isAllowAutoMetrics()));

        ConsoleHelper.info("A default permission can be created automatically for every new cohort; it also configures federated queries.");
        if (!prompter.confirm("--default-permission", given.getDefaultPermission(), "Create a default permission for every new cohort?",
                client.isDefaultPermission())) {
            client.disableDefaultPermission(config.client().permission().queryRetryTime(), config.client().permission().querySampleThreshold());
            return;
        }
        client.setDefaultPermission(true);
        client.setPermissionUser(prompter.text("--permission-user", given.getPermissionUser(),
                "FL-Net user ID the permission is for (empty = any user)", client.getPermissionUser(), Validator.ANY));
        List<AutoAccess> allOrNone = List.of(AutoAccess.ALL, AutoAccess.NONE);
        client.setAutoStatistics(!client.isAllowAutoStatistics() ? AutoAccess.NONE
                : prompter.choice("--auto-statistics", given.getAutoStatistics(), "Automatic STATISTICS access ('none' = manual approval)",
                allOrNone, AutoAccess::cliName, client.getAutoStatistics()));
        client.setAutoMetrics(!client.isAllowAutoMetrics() ? AutoAccess.NONE
                : prompter.choice("--auto-metrics", given.getAutoMetrics(), "Automatic METRICS access ('none' = manual approval)",
                allOrNone, AutoAccess::cliName, client.getAutoMetrics()));
        if (client.isAllowAutoLearning()) {
            ConsoleHelper.info("Tools that need internet or host access always require manual approval, regardless of this setting.");
        }
        client.setAutoLearning(!client.isAllowAutoLearning() ? AutoAccess.NONE
                : prompter.choice("--auto-learning", given.getAutoLearning(), "Automatic LEARNING result access ('certified' = only certified tools)",
                List.of(AutoAccess.values()), AutoAccess::cliName, client.getAutoLearning()));

        ConsoleHelper.info("Federated queries are answered automatically when allowed, protected by thresholding and rate limiting.");
        client.setAllowQueries(prompter.confirm("--allow-queries", given.getAllowQueries(), "Allow federated queries against this client?",
                client.isAllowQueries()));
        if (client.isAllowQueries()) {
            client.setQueryRetryTime(prompter.integer("--query-retry-time", given.getQueryRetryTime(),
                    "Minimum seconds between repeated queries", client.getQueryRetryTime(), 0));
            client.setQuerySampleThreshold(prompter.integer("--query-sample-threshold", given.getQuerySampleThreshold(),
                    "Minimum sample size before a query is answered", client.getQuerySampleThreshold(), 0));
        }
    }


    private void webAccess(ClientConfig given, FLNetClientDeployment client, PortPlanner ports) {
        section("4. Web access",
                "The client is used in the browser. Listen on localhost (this machine only) or expose it, e.g. on 0.0.0.0.",
                "If you expose it, restrict access to your internal network or a VPN.");
        client.setListen(prompter.text("--listen", given.getListen(), "Address to listen on (localhost or IPv4)", client.getListen(),
                value -> value.equals("localhost") || value.equals("127.0.0.1") ? null : NetworkHelper.validateIpv4(value)));

        ConsoleHelper.info("Optionally set the domain of the client: it enables strict CORS/host checks and SSL termination.");
        String domain = prompter.text("--domain", given.getDomain(), "Domain incl. protocol (empty or 'none' for no domain)",
                client.getDomain() == null ? "" : client.getDomain().toString(),
                value -> isNone(value) ? null : WebAddress.validate(value));
        client.setDomain(isNone(domain) ? null : WebAddress.parse(domain));
        ssl(given, client);

        int preferred = client.getDomain() != null && client.isSslEnabled() ? client.getDomain().port() : client.getPort();
        client.setPort(port(ports, "--port", given.getPort(), "Port the client listens on", preferred, config.client().port()));
        warnings.check(client);
    }

    private void ssl(ClientConfig given, FLNetClientDeployment client) {
        if (client.getDomain() == null) {
            if (given.getSsl() != null && !given.getSsl().equals(SslSource.NONE.cliName())) {
                throw CliException.usage("--ssl " + given.getSsl() + " requires --domain.");
            }
            client.useSsl(SslSource.NONE, null, null);
            return;
        }
        SslSource source = prompter.choice("--ssl", given.getSsl(), "SSL termination in the client: none (e.g. your reverse proxy does it), "
                + "provided certificate files, or self-signed", List.of(SslSource.values()), SslSource::cliName, client.getSslSource());
        if (source != SslSource.PROVIDED) {
            client.useSsl(source, null, null);
            return;
        }
        boolean wasProvided = client.getSslSource() == SslSource.PROVIDED;
        client.useSsl(SslSource.PROVIDED,
                file("--ssl-cert", given.getSslCert(), "Certificate chain file (fullchain.pem)", wasProvided ? client.getSslCertificate() : null, true),
                file("--ssl-key", given.getSslKey(), "Private key file (privkey.pem)", wasProvided ? client.getSslPrivateKey() : null, true));
        prompter.acknowledge("Certificates are NOT renewed or reloaded automatically. After renewing (e.g. certbot deploy hook) run: "
                + "docker compose exec reverse-proxy-encrypted nginx -s reload");
    }

    private void certificate(ClientConfig given, FLNetClientDeployment client) {
        boolean missing = client.getSslSource() == SslSource.SELF_SIGNED && !Files.exists(client.getSelfSignedCertificate());
        if (!missing) {
            given.setCreateCertificate(false);
            return;
        }
        boolean opensslInstalled = certificates.opensslVersion().isPresent();
        given.setCreateCertificate(prompter.confirm("--create-certificate", given.getCreateCertificate(),
                "Create the self-signed certificate now?", opensslInstalled));
        if (given.getCreateCertificate()) {
            certificates.requireOpenssl();
            certificateQuestionnaire.ask(given.getCertificate(), client);
        }
    }

    private static boolean isNone(String value) {
        return value.isEmpty() || value.equalsIgnoreCase("none");
    }
}

package bio.cosy.flnet.cli.sandbox.bo;

import bio.cosy.flnet.cli.base.deployment.AutoAccess;
import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.base.deployment.FLNetClientDeployment;
import bio.cosy.flnet.cli.base.deployment.FLNetPlatformDeployment;
import bio.cosy.flnet.cli.base.deployment.SslSource;
import bio.cosy.flnet.cli.client.bo.FLNetClientDeploymentBO;
import bio.cosy.flnet.cli.config.FLNetCliConfig;
import bio.cosy.flnet.cli.deploy.DeploymentBundleHelper;
import bio.cosy.flnet.cli.deploy.PortPlanner;
import bio.cosy.flnet.cli.deploy.bo.BaseFLNetDeploymentBO;
import bio.cosy.flnet.cli.deploy.bo.ComposeBO;
import bio.cosy.flnet.cli.deploy.bo.FLNetDeploymentsBO;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.EnvFileHelper;
import bio.cosy.flnet.cli.helper.WebAddress;
import bio.cosy.flnet.cli.platform.bo.FLNetPlatformDeploymentBO;
import bio.cosy.flnet.cli.sandbox.FLNetSandbox;
import bio.cosy.flnet.cli.sandbox.SandboxImage;
import bio.cosy.flnet.cli.sandbox.SandboxModifier;
import bio.cosy.flnet.cli.sandbox.SandboxModifier.ComposeOverride;
import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

@ApplicationScoped
public class FLNetSandboxBO {

    public static final String BUNDLE = "sandbox";
    public static final String TEST_USER = "test";
    public static final String TEST_PASSWORD = "test";

    /** How containers reach services published on the host, i.e. how the clients reach the platform. */
    private static final String HOST_FROM_CONTAINER = "host.docker.internal";
    private static final String HOST_GATEWAY = HOST_FROM_CONTAINER + ":host-gateway";
    private static final String PIPELINE_BUILDER_IMAGE = "ghcr.io/fedlearnnet/tool-build-pipeline/pipeline";
    private static final String REALM_FILE = "keycloak-realms/realm-export.json";
    private static final String SCHEMA_FILE = "schemas/2026-04us130_diabetes_schema_no_umls.csv";
    private static final String CONNECTOR_FILE = "us-130_connector.json";
    private static final String SEED_SCRIPT = "dataGenerator_CLI.py";

    // nginx.conf of the platform: pushes to the tool registry need no login in the sandbox
    private static final String REGISTRY_GUARD = """
                    if ($registry_denied) {
                        add_header WWW-Authenticate 'Basic realm="FL-Net tool registry"' always;
                        add_header Docker-Distribution-Api-Version registry/2.0 always;
                        return 401;
                    }
            """;
    private static final String REGISTRY_GUARD_DISABLED = """
                    # Sandbox: pushes need no login. The build pipeline pushes through the docker daemon of the
                    # host (buildx cannot reach a registry on localhost), which only logs in when GET /v2/ asks for it.
                    # if ($registry_denied) {
                    #     add_header WWW-Authenticate 'Basic realm="FL-Net tool registry"' always;
                    #     add_header Docker-Distribution-Api-Version registry/2.0 always;
                    #     return 401;
                    # }
            """;
    // nginx.conf of the platform: keep the port, otherwise Keycloak drops it from the URLs it hands to the clients
    private static final String KEYCLOAK_HOST = """
                    proxy_pass http://keycloak-backend/auth/;
                    proxy_set_header Host              $host;
            """;
    private static final String KEYCLOAK_HOST_WITH_PORT = """
                    proxy_pass http://keycloak-backend/auth/;
                    proxy_set_header Host              $http_host;
                    proxy_set_header X-Forwarded-Host  $http_host;
            """;

    @Inject
    FLNetCliConfig config;

    @Inject
    FLNetDeploymentsBO deployments;

    @Inject
    FLNetPlatformDeploymentBO platformBO;

    @Inject
    FLNetClientDeploymentBO clientBO;

    @Inject
    ComposeBO composeBO;

    @Inject
    Engine qute;

    public Path directory() {
        return deployments.sandboxDir();
    }

    public Optional<FLNetSandbox> find() {
        Optional<FLNetPlatformDeployment> platform = deployments.sandboxPlatform();
        if (platform.isEmpty()) {
            return Optional.empty();
        }
        FLNetSandbox sandbox = new FLNetSandbox(directory());
        sandbox.setPlatform(platform.get());
        sandbox.getClients().addAll(deployments.sandboxClients());
        sandbox.getImages().putAll(readImages(sandbox.getImagesFile()));
        return Optional.of(sandbox);
    }

    public FLNetSandbox require() {
        return find().orElseThrow(() -> new CliException("No sandbox is set up on this machine (" + directory()
                + "). Run 'flnet sandbox init' first.", CliException.ENVIRONMENT));
    }


    /**
     * Creates the sandbox or reconfigures the existing one. Values that are not given keep what the sandbox has,
     * or the configured default for a new one. Secrets are kept.
     */
    public FLNetSandbox configure(Integer clientCount, Integer port, Integer relayPort, String imageTag) {
        Path dir = directory();
        FLNetSandbox sandbox = new FLNetSandbox(dir);
        sandbox.getImages().putAll(readImages(sandbox.getImagesFile()));
        List<FLNetClientDeployment> existingClients = deployments.sandboxClients();
        int clients = clientCount != null ? clientCount : existingClients.isEmpty() ? config.sandbox().clients() : existingClients.size();
        if (clients < 1) {
            throw CliException.usage("Invalid value for --clients: a sandbox needs at least one client.");
        }
        if (clients < existingClients.size()) {
            throw CliException.usage("The sandbox has " + existingClients.size() + " clients; clients cannot be removed "
                    + "one by one. Remove the sandbox with 'flnet sandbox clean --purge' and set it up again.");
        }

        FLNetPlatformDeployment platform = loadOrNew(platformBO, FLNetDeploymentsBO.SANDBOX_PLATFORM,
                dir.resolve(FLNetDeploymentsBO.SANDBOX_PLATFORM));
        String tag = imageTag != null ? imageTag : platform.isInitialized() ? platform.getImageTag() : config.sandbox().imageTag();
        PortPlanner ports = platformBO.portPlanner(platform);
        int nginxPort = port(ports, "--port", port, platform.isInitialized() ? platform.getNginxPort() : config.sandbox().port());
        ports.claim(nginxPort, "the sandbox platform");
        int relay = port(ports, "--relay-port", relayPort, platform.isInitialized() ? platform.getRelayPort() : config.sandbox().relayPort());
        configurePlatform(platform, nginxPort, relay, tag);
        platformBO.ensureSecrets(platform);
        platformBO.save(platform, true, null);
        sandbox.setPlatform(platform);

        for (int i = 1; i <= clients; i++) {
            String name = "c" + i;
            FLNetClientDeployment client = loadOrNew(clientBO, name, dir.resolve(FLNetDeploymentsBO.SANDBOX_CLIENTS).resolve(name));
            // saved one after the other, so each one sees the ports of the members before it
            int clientPort = port(clientBO.portPlanner(client), null, null, client.isInitialized() ? client.getPort() : nginxPort + i);
            configureClient(client, platform, clientPort, tag);
            clientBO.ensureSecrets(client);
            clientBO.save(client, true, null);
            sandbox.getClients().add(client);
        }

        makeSandbox(sandbox);
        DeploymentBundleHelper.extract(BUNDLE, FLNetSandbox.SEED_FOLDER, sandbox.getSeedDirectory());
        writeReadme(sandbox);
        return sandbox;
    }

    private static <T extends BaseFLNetDeployableInstance> T loadOrNew(BaseFLNetDeploymentBO<T> bo, String name, Path dir) {
        return Files.isRegularFile(dir.resolve(".env")) ? bo.load(dir, name) : bo.newInstance(name, dir);
    }

    private static int port(PortPlanner ports, String flag, Integer given, int preferred) {
        if (given == null) {
            return ports.suggest(preferred, preferred);
        }
        String error = ports.validate(String.valueOf(given));
        if (error != null) {
            throw CliException.usage("Invalid value for " + flag + ": " + error);
        }
        return given;
    }

    private void configurePlatform(FLNetPlatformDeployment platform, int nginxPort, int relayPort, String imageTag) {
        platform.setSandbox(true);
        platform.setProjectName(config.sandbox().projectName() + "-" + platform.getName());
        platform.setImageTag(imageTag);
        platformBO.applyDomain(platform, WebAddress.parse("http://localhost:" + nginxPort));
        // the clients come in through the docker host gateway, which a port bound to 127.0.0.1 does not accept
        platform.setBindIp("0.0.0.0");
        platform.setNginxPort(nginxPort);
        platform.setRelayPort(relayPort);
        platform.setMinClients(1);
        platform.setClientAuth(true);
        platform.setPlatformAggregatorEnabled(true);
        platform.setSslEnabled(false);
        platform.setSslCertificate(null);
        platform.setSslPrivateKey(null);
    }

    private void configureClient(FLNetClientDeployment client, FLNetPlatformDeployment platform, int port, String imageTag) {
        client.setSandbox(true);
        client.setProjectName(config.sandbox().projectName() + "-" + client.getName());
        client.setImageTag(imageTag);
        // derived again, so a new image tag reaches the frontend too
        client.setFrontendImage(null);
        clientBO.applyCustomPlatform(client, platformFromContainer(platform), platform.getRelayPort(), true);
        // tools are pulled by the docker daemon of the host, which reaches the platform as localhost
        client.setToolRegistry(platform.getToolRegistry());
        client.setFederation(true);
        client.setPlatformUsername(TEST_USER);
        client.setPlatformPassword(TEST_PASSWORD);
        client.setListen("localhost");
        client.setPort(port);
        client.setDomain(null);
        client.useSsl(SslSource.NONE, null, null);
        client.setAllowAutoStatistics(true);
        client.setAllowAutoLearning(true);
        client.setAllowAutoMetrics(true);
        client.setDefaultPermission(true);
        client.setAllowQueries(true);
        client.setAutoStatistics(AutoAccess.ALL);
        client.setAutoLearning(AutoAccess.ALL);
        client.setAutoMetrics(AutoAccess.ALL);
    }

    private static WebAddress platformFromContainer(FLNetPlatformDeployment platform) {
        return WebAddress.parse("http://" + HOST_FROM_CONTAINER + ":" + platform.getNginxPort());
    }


    /**
     * Turns the freshly installed production deployments of the sandbox into their sandbox variant. Everything
     * that differs from production is in here.
     */
    void makeSandbox(FLNetSandbox sandbox) {
        FLNetPlatformDeployment platform = sandbox.getPlatform();
        Path nginx = platform.resolve("nginx.conf");
        SandboxModifier.findAndReplace(nginx, REGISTRY_GUARD, REGISTRY_GUARD_DISABLED);
        SandboxModifier.findAndReplace(nginx, KEYCLOAK_HOST, KEYCLOAK_HOST_WITH_PORT);
        String platformUser = DeploymentBundleHelper.resource(BUNDLE, "platform-test-user.json");
        SandboxModifier.patchRealm(platform.resolve(REALM_FILE), realm -> {
            SandboxModifier.addUser(realm, platformUser);
            SandboxModifier.addRedirectUri(realm, "account-console", platform.getDomain().hostWithPort() + "/*");
        });
        String clientUser = DeploymentBundleHelper.resource(BUNDLE, "client-test-user.json");
        for (FLNetClientDeployment client : sandbox.getClients()) {
            SandboxModifier.patchRealm(client.resolve(REALM_FILE), realm -> {
                SandboxModifier.addUser(realm, clientUser);
                // the seed script logs in with the password of the test user
                SandboxModifier.client(realm, "frontend").put("directAccessGrantsEnabled", true);
            });
        }
        writeOverrides(sandbox);
    }

    void writeOverrides(FLNetSandbox sandbox) {
        FLNetPlatformDeployment platform = sandbox.getPlatform();
        Map<SandboxImage, String> images = sandbox.getImages();
        String corsOrigins = "${DEPLOYED_ON_DOMAIN}," + platformFromContainer(platform);

        ComposeOverride platformOverride = new ComposeOverride();
        // The relay creates its own certificate, so none is mounted. The paths must be empty then: the relay
        // does not start with a configured path that does not exist.
        platformOverride.service("relay-server")
                .env("RELAY_DOMAIN", HOST_FROM_CONTAINER)
                .env("RELAY_TLS_MODE", "self-signed")
                .env("RELAY_MODE", "dev")
                .env("RELAY_PUBLIC_CERT_PATH", "")
                .env("RELAY_PRIVATE_KEY_PATH", "")
                .resetVolumes();
        // buildx runs in its own container and cannot reach the registry on localhost
        platformOverride.service("orch-api").env("PIPELINE_USE_BUILDX", "false");
        // the second origin is the address the client containers use
        platformOverride.service("datamodeler-api").env("QUARKUS_HTTP_CORS_ORIGINS", corsOrigins);
        platformOverride.service("global-learning-api")
                .env("QUARKUS_HTTP_CORS_ORIGINS", corsOrigins)
                .env("FLNET_PIPELINE_BUILDER_IMAGE", images.getOrDefault(SandboxImage.PIPELINE_BUILDER,
                        PIPELINE_BUILDER_IMAGE + ":" + platform.getImageTag()));
        // accept the clients although they use another host name than the browser
        platformOverride.service("keycloak").env("KC_HOSTNAME_BACKCHANNEL_DYNAMIC", "true");
        images.forEach((image, value) -> {
            if (image.platformService() != null) {
                platformOverride.service(image.platformService()).image(value);
            }
        });
        SandboxModifier.writeComposeOverride(platform.getDirectory(), platformOverride);

        for (FLNetClientDeployment client : sandbox.getClients()) {
            ComposeOverride clientOverride = new ComposeOverride();
            clientOverride.service("local-learning-api")
                    .extraHost(HOST_GATEWAY)
                    // GLOBAL_DOMAIN carries the port of the platform, GLOBAL_FEDERATION_HOST does not
                    .env("FLNET_GLOBAL_SOCKET_URI", "${GLOBAL_WS_PROTOCOL}://${GLOBAL_DOMAIN}/api");
            clientOverride.service("controller")
                    .extraHost(HOST_GATEWAY)
                    .env("RELAY_TLS_MODE", "self-signed")
                    .env("RELAY_MODE", "dev");
            images.forEach((image, value) -> {
                if (image.clientService() != null) {
                    clientOverride.service(image.clientService()).image(value);
                }
            });
            SandboxModifier.writeComposeOverride(client.getDirectory(), clientOverride);
        }
    }


    public void setImage(FLNetSandbox sandbox, SandboxImage image, String value) {
        sandbox.getImages().put(image, value);
        saveImages(sandbox);
    }

    public void resetImages(FLNetSandbox sandbox, SandboxImage image) {
        if (image == null) {
            sandbox.getImages().clear();
        } else {
            sandbox.getImages().remove(image);
        }
        saveImages(sandbox);
    }

    private void saveImages(FLNetSandbox sandbox) {
        Map<String, String> byKey = new LinkedHashMap<>();
        sandbox.getImages().forEach((image, value) -> byKey.put(image.key(), value));
        EnvFileHelper.write(sandbox.getImagesFile(), byKey);
        writeOverrides(sandbox);
        writeReadme(sandbox);
    }

    private static Map<SandboxImage, String> readImages(Path file) {
        Map<SandboxImage, String> images = new LinkedHashMap<>();
        EnvFileHelper.read(file).forEach((key, value) -> SandboxImage.byKey(key).ifPresent(image -> {
            if (!value.isBlank()) {
                images.put(image, value);
            }
        }));
        return images;
    }

    /** The images of all members except the replaced ones, which only exist on this machine. */
    public Set<String> pullableImages(FLNetSandbox sandbox) {
        Set<String> images = new LinkedHashSet<>();
        sandbox.getMembers().forEach(member -> images.addAll(composeBO.images(member)));
        images.removeAll(sandbox.getImages().values());
        return images;
    }


    /** The data file of each client that gets one, in the order of the clients. */
    public Map<FLNetClientDeployment, String> seedData(FLNetSandbox sandbox) {
        List<String> files;
        try (Stream<Path> entries = Files.list(sandbox.getSeedDirectory())) {
            files = entries.map(file -> file.getFileName().toString())
                    .filter(name -> name.startsWith("clinic_") && name.endsWith(".csv"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException e) {
            throw new CliException("The seed data is missing in " + sandbox.getSeedDirectory()
                    + ". Run 'flnet sandbox init' to restore it.", CliException.ENVIRONMENT);
        }
        Map<FLNetClientDeployment, String> data = new LinkedHashMap<>();
        for (int i = 0; i < sandbox.getClients().size() && i < files.size(); i++) {
            data.put(sandbox.getClients().get(i), files.get(i));
        }
        return data;
    }

    /** The commands that fill the sandbox with data, to be run in the seed directory. */
    public List<List<String>> seedCommands(FLNetSandbox sandbox) {
        String datamodeler = sandbox.getPlatform().getAddress() + "/data-modeler";
        List<String> setup = new ArrayList<>(List.of("python3", SEED_SCRIPT, "setup-clients", "--global-datamodeler-url", datamodeler,
                "--connector-json", CONNECTOR_FILE));
        seedData(sandbox).forEach((client, file) -> setup.addAll(List.of("--client", client.getAddress(), file)));
        setup.add(SCHEMA_FILE);
        return List.of(
                List.of("python3", SEED_SCRIPT, "upload-schema", "--global-datamodeler-url", datamodeler, SCHEMA_FILE),
                setup);
    }

    public List<String> datasetNotice() {
        return DeploymentBundleHelper.resource(BUNDLE, FLNetSandbox.SEED_FOLDER + "/DATASET.md").lines()
                .filter(line -> !line.startsWith("#") && !line.isBlank())
                .toList();
    }

    public List<String> securityNotice(FLNetSandbox sandbox) {
        return List.of("The sandbox is for local testing only: the platform listens on all network interfaces (ports "
                        + sandbox.getPlatform().getNginxPort() + " and " + sandbox.getPlatform().getRelayPort() + "), everyone can log in as '"
                        + TEST_USER + "' with password '" + TEST_PASSWORD + "' and push to its tool registry.",
                "Do not run it on a machine that is reachable from an untrusted network, and never put real data in it.");
    }

    private void writeReadme(FLNetSandbox sandbox) {
        Template template = qute.getTemplate("sandbox/README.md");
        if (template == null) {
            throw new IllegalStateException("Template sandbox/README.md is missing, the CLI was built incorrectly.");
        }
        Map<FLNetClientDeployment, String> data = seedData(sandbox);
        List<Map<String, String>> clients = sandbox.getClients().stream().map(client -> Map.of(
                "name", client.getName(),
                "url", client.getAddress(),
                "folder", FLNetDeploymentsBO.SANDBOX_CLIENTS + "/" + client.getName(),
                "data", data.getOrDefault(client, "no data file"))).toList();
        List<Map<String, String>> images = sandbox.getImages().entrySet().stream()
                .map(entry -> Map.of("key", entry.getKey().key(), "image", entry.getValue())).toList();
        String content = template
                .data("directory", sandbox.getDirectory().toString())
                .data("platformUrl", sandbox.getPlatform().getAddress())
                .data("relayPort", sandbox.getPlatform().getRelayPort())
                .data("user", TEST_USER)
                .data("password", TEST_PASSWORD)
                .data("clients", clients)
                .data("images", images)
                .data("imageKeys", SandboxImage.keys())
                .data("seedCommands", seedCommands(sandbox).stream().map(command -> String.join(" ", command)).toList())
                .data("securityNotice", String.join(" ", securityNotice(sandbox)))
                .data("datasetNotice", String.join("\n", datasetNotice()))
                .render();
        try {
            Files.writeString(sandbox.getReadme(), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + sandbox.getReadme(), e);
        }
    }


    public void delete(FLNetSandbox sandbox) {
        try (Stream<Path> files = Files.walk(sandbox.getDirectory())) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete " + sandbox.getDirectory(), e);
        }
    }
}

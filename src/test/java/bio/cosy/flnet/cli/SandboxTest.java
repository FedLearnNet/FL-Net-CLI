package bio.cosy.flnet.cli;

import bio.cosy.flnet.cli.helper.EnvFileHelper;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusMainTest
class SandboxTest {

    @TempDir
    Path home;

    @BeforeEach
    void isolateHome() {
        System.setProperty("flnet.home", home.toString());
    }

    @AfterEach
    void resetHome() {
        System.clearProperty("flnet.home");
    }

    /**
     * Also the guard for the deployment bundles: init fails when the production nginx.conf or realm no longer
     * contain what the sandbox modifications replace.
     */
    @Test
    void createsAConnectedPlatformAndClientsFromTheProductionBundles(QuarkusMainLauncher launcher) throws IOException {
        LaunchResult result = launcher.launch("sandbox", "init");
        assertEquals(0, result.exitCode(), result.getErrorOutput());
        assertTrue(result.getOutput().contains("https://doi.org/10.24432/C5230J"), result.getOutput());
        assertTrue(result.getOutput().contains("flnet sandbox images set <service> <image>"), result.getOutput());
        assertTrue(result.getErrorOutput().contains("local testing only"), result.getErrorOutput());

        Path sandbox = home.resolve("sandbox");
        Map<String, String> platform = EnvFileHelper.read(sandbox.resolve("platform/.env"));
        String port = platform.get("NGINX_PORT").replace("0.0.0.0:", "");
        assertEquals("true", platform.get("FLNET_SANDBOX"));
        assertEquals("fl-net-sandbox-platform", platform.get("COMPOSE_PROJECT_NAME"));
        assertEquals("http://localhost:" + port, platform.get("DEPLOYED_ON_DOMAIN"));
        assertEquals("no-ssl", platform.get("COMPOSE_PROFILES"));
        assertEquals("1", platform.get("MIN_CLIENTS_NEEDED_FOR_LEARNING"));
        assertEquals("staging", platform.get("IMAGE_TAG"));
        assertEquals("localhost:" + port, platform.get("TOOL_REGISTRY"));
        // docker compose refuses an empty path, also in the volumes of the unused ssl profile
        assertFalse(platform.get("SSL_CERT_PUBLIC_KEY").isEmpty());

        Map<String, String> c1 = EnvFileHelper.read(sandbox.resolve("clients/c1/.env"));
        Map<String, String> c2 = EnvFileHelper.read(sandbox.resolve("clients/c2/.env"));
        assertEquals("fl-net-sandbox-c1", c1.get("COMPOSE_PROJECT_NAME"));
        assertEquals("host.docker.internal:" + port, c1.get("GLOBAL_DOMAIN"));
        assertEquals("localhost:" + port, c1.get("TOOL_REGISTRY"));
        assertEquals(platform.get("EXPOSED_RELAY_TCP_PORT").replace("0.0.0.0:", ""), c1.get("GLOBAL_TCP_PORT"));
        assertEquals("true", c1.get("GLOBAL_KEYCLOAK_ENABLED"));
        assertEquals("ALL", c1.get("COHORT_PERMISSION_AUTO_TRAINING_ACCESS"));
        assertEquals("http://localhost:" + c1.get("EXPOSED_PORT"), c1.get("DEPLOYED_ON_ADDRESS"));
        assertNotEquals(c1.get("EXPOSED_PORT"), c2.get("EXPOSED_PORT"));
        assertNotEquals(port, c1.get("EXPOSED_PORT"));
        assertEquals("test", EnvFileHelper.read(sandbox.resolve("clients/c1/env/local-learning-secrets.env")).get("FLNET_GLOBAL_AUTH_USERNAME"));

        // the production files are installed unchanged, the differences are in the override
        assertEquals(Files.readString(Path.of("src/main/resources/bundles/platform/docker-compose.yml")),
                Files.readString(sandbox.resolve("platform/docker-compose.yml")));
        String platformOverride = Files.readString(sandbox.resolve("platform/docker-compose.override.yml"));
        assertTrue(platformOverride.contains("RELAY_TLS_MODE: \"self-signed\""), platformOverride);
        assertTrue(platformOverride.contains("volumes: !reset []"), platformOverride);
        // without the mounted certificate the relay only starts when no path is configured
        assertTrue(platformOverride.contains("RELAY_PUBLIC_CERT_PATH: \"\""), platformOverride);
        assertTrue(platformOverride.contains("QUARKUS_HTTP_CORS_ORIGINS: \"${DEPLOYED_ON_DOMAIN},http://host.docker.internal:" + port + "\""), platformOverride);
        assertTrue(platformOverride.contains("pipeline:staging"), platformOverride);
        String clientOverride = Files.readString(sandbox.resolve("clients/c2/docker-compose.override.yml"));
        assertTrue(clientOverride.contains("host.docker.internal:host-gateway"), clientOverride);
        assertTrue(clientOverride.contains("FLNET_GLOBAL_SOCKET_URI: \"${GLOBAL_WS_PROTOCOL}://${GLOBAL_DOMAIN}/api\""), clientOverride);

        String nginx = Files.readString(sandbox.resolve("platform/nginx.conf"));
        assertTrue(nginx.contains("# if ($registry_denied) {"), nginx);
        assertFalse(nginx.contains("\n        if ($registry_denied) {"), nginx);
        assertTrue(nginx.contains("proxy_set_header X-Forwarded-Host  $http_host;"), nginx);

        String platformRealm = Files.readString(sandbox.resolve("platform/keycloak-realms/realm-export.json"));
        assertTrue(platformRealm.contains("\"username\" : \"test\""), "test user in the platform realm");
        assertTrue(platformRealm.contains("\"localhost:" + port + "/*\""), "redirect uri in the platform realm");
        String clientRealm = Files.readString(sandbox.resolve("clients/c1/keycloak-realms/realm-export.json"));
        assertTrue(clientRealm.contains("\"username\" : \"test\""), "test user in the client realm");
        assertTrue(clientRealm.contains("\"/Data-Access-Manager\""), "groups of the client test user");

        assertTrue(Files.exists(sandbox.resolve("seed/clinic_001.csv")));
        assertTrue(Files.exists(sandbox.resolve("seed/requirements.txt")));
        assertTrue(Files.exists(sandbox.resolve("seed/helper/auth.py")));
        assertTrue(Files.readString(sandbox.resolve("seed/DATASET.md")).contains("CC BY 4.0"));
        String readme = Files.readString(sandbox.resolve("README.md"));
        assertTrue(readme.contains("| c2 | http://localhost:" + c2.get("EXPOSED_PORT") + " | `clients/c2/` |"), readme);
        assertTrue(readme.contains("--client http://localhost:" + c1.get("EXPOSED_PORT") + " clinic_001.csv"), readme);
        assertTrue(readme.contains("https://doi.org/10.24432/C5230J"), readme);
        assertFalse(readme.contains("{"), "unrendered template expression: " + readme);
    }

    @Test
    void staysOutOfClientAndPlatformCommands(QuarkusMainLauncher launcher) throws IOException {
        assertEquals(0, launcher.launch("sandbox", "init").exitCode());
        assertFalse(Files.exists(home.resolve("clients")), "no sandbox client is linked into clients/");
        assertFalse(Files.exists(home.resolve("platforms")), "the sandbox platform is not linked into platforms/");

        LaunchResult list = launcher.launch("list");
        assertTrue(list.getOutput().contains("Nothing is set up on this machine yet"), list.getOutput());
        assertTrue(list.getOutput().contains("flnet sandbox status"), list.getOutput());
        assertTrue(launcher.launch("client", "list").getOutput().contains("No FL-Net Client is set up"));
        assertEquals(3, launcher.launch("client", "status", "--no-input").exitCode());

        // a client next to the sandbox is the only client, and does not get a port of the sandbox
        LaunchResult client = launcher.launch("client", "init", "--no-input", "--network", "daibetes");
        assertEquals(0, client.exitCode(), client.getErrorOutput());
        String clientPort = EnvFileHelper.read(home.resolve("clients/default-deployment/.env")).get("EXPOSED_PORT");
        Path sandbox = home.resolve("sandbox");
        assertNotEquals(EnvFileHelper.read(sandbox.resolve("platform/.env")).get("NGINX_PORT").replace("0.0.0.0:", ""), clientPort);
        assertNotEquals(EnvFileHelper.read(sandbox.resolve("clients/c1/.env")).get("EXPOSED_PORT"), clientPort);
        assertNotEquals(EnvFileHelper.read(sandbox.resolve("clients/c2/.env")).get("EXPOSED_PORT"), clientPort);
        LaunchResult info = launcher.launch("client", "info");
        assertTrue(info.getOutput().contains("FL-Net Client 'default-deployment'"), info.getOutput());
    }

    @Test
    void reconfiguresWithoutChangingSecretsOrPorts(QuarkusMainLauncher launcher) throws IOException {
        assertEquals(0, launcher.launch("sandbox", "init").exitCode());
        Path sandbox = home.resolve("sandbox");
        String platformSecrets = Files.readString(sandbox.resolve("platform/env/keycloak-secrets.env"));
        String clientSecrets = Files.readString(sandbox.resolve("clients/c1/env/local-learning-secrets.env"));
        Map<String, String> c1 = EnvFileHelper.read(sandbox.resolve("clients/c1/.env"));

        LaunchResult again = launcher.launch("sandbox", "init", "--clients", "3", "--image-tag", "latest");
        assertEquals(0, again.exitCode(), again.getErrorOutput());
        assertTrue(again.getOutput().contains("Reconfigured the sandbox"), again.getOutput());
        assertEquals(platformSecrets, Files.readString(sandbox.resolve("platform/env/keycloak-secrets.env")));
        assertEquals(clientSecrets, Files.readString(sandbox.resolve("clients/c1/env/local-learning-secrets.env")));
        Map<String, String> c1Again = EnvFileHelper.read(sandbox.resolve("clients/c1/.env"));
        assertEquals(c1.get("EXPOSED_PORT"), c1Again.get("EXPOSED_PORT"));
        assertEquals("latest", c1Again.get("IMAGE_TAG"));
        assertTrue(c1Again.get("FRONTEND_IMAGE").endsWith(":latest"), c1Again.get("FRONTEND_IMAGE"));
        assertTrue(Files.exists(sandbox.resolve("clients/c3/.env")));
        // the modifications are applied to freshly installed files, not stacked
        String nginx = Files.readString(sandbox.resolve("platform/nginx.conf"));
        assertEquals(nginx.indexOf("# if ($registry_denied) {"), nginx.lastIndexOf("# if ($registry_denied) {"));

        LaunchResult fewer = launcher.launch("sandbox", "init", "--clients", "2");
        assertEquals(2, fewer.exitCode());
        assertTrue(fewer.getErrorOutput().contains("flnet sandbox clean --purge"), fewer.getErrorOutput());
    }

    @Test
    void replacesAndResetsImages(QuarkusMainLauncher launcher) throws IOException {
        assertEquals(3, launcher.launch("sandbox", "images", "set", "orch-api", "orch-api:dev").exitCode());
        assertEquals(0, launcher.launch("sandbox", "init").exitCode());
        Path sandbox = home.resolve("sandbox");

        LaunchResult set = launcher.launch("sandbox", "images", "set", "orch-api", "orch-api:dev");
        assertEquals(0, set.exitCode(), set.getErrorOutput());
        assertEquals(0, launcher.launch("sandbox", "images", "set", "local-frontend", "local-fl-net:dev").exitCode());
        assertEquals(0, launcher.launch("sandbox", "images", "set", "pipeline-builder", "pipeline:dev").exitCode());
        assertEquals(Map.of("orch-api", "orch-api:dev", "local-frontend", "local-fl-net:dev", "pipeline-builder", "pipeline:dev"),
                EnvFileHelper.read(sandbox.resolve("images.env")));
        String platformOverride = Files.readString(sandbox.resolve("platform/docker-compose.override.yml"));
        assertTrue(platformOverride.contains("  orch-api:\n    image: \"orch-api:dev\""), platformOverride);
        assertTrue(platformOverride.contains("FLNET_PIPELINE_BUILDER_IMAGE: \"pipeline:dev\""), platformOverride);
        assertFalse(platformOverride.contains("local-fl-net:dev"), platformOverride);
        String clientOverride = Files.readString(sandbox.resolve("clients/c1/docker-compose.override.yml"));
        assertTrue(clientOverride.contains("  orch-api:\n    image: \"orch-api:dev\""), clientOverride);
        assertTrue(clientOverride.contains("  instance-manager-frontend:\n    image: \"local-fl-net:dev\""), clientOverride);
        assertTrue(Files.readString(sandbox.resolve("README.md")).contains("- `orch-api`: `orch-api:dev`"));

        // replaced images survive a reconfiguration
        assertEquals(0, launcher.launch("sandbox", "init").exitCode());
        assertTrue(Files.readString(sandbox.resolve("clients/c2/docker-compose.override.yml")).contains("orch-api:dev"));

        assertEquals(0, launcher.launch("sandbox", "images", "reset", "orch-api").exitCode());
        assertFalse(Files.readString(sandbox.resolve("platform/docker-compose.override.yml")).contains("orch-api:dev"));
        assertTrue(Files.readString(sandbox.resolve("clients/c1/docker-compose.override.yml")).contains("local-fl-net:dev"));
        assertEquals(0, launcher.launch("sandbox", "images", "reset").exitCode());
        assertTrue(EnvFileHelper.read(sandbox.resolve("images.env")).isEmpty());

        LaunchResult unknown = launcher.launch("sandbox", "images", "set", "nginx", "nginx:dev");
        assertEquals(2, unknown.exitCode());
        assertTrue(unknown.getErrorOutput().contains("Unknown service 'nginx'"), unknown.getErrorOutput());
    }

    @Test
    void purgesTheSandboxOnlyAfterConfirmation(QuarkusMainLauncher launcher) {
        assertEquals(3, launcher.launch("sandbox", "status").exitCode());
        assertEquals(0, launcher.launch("sandbox", "init").exitCode());
        LaunchResult unconfirmed = launcher.launch("sandbox", "clean", "--purge", "--no-input");
        assertEquals(2, unconfirmed.exitCode());
        assertTrue(Files.exists(home.resolve("sandbox/platform/.env")));
        LaunchResult member = launcher.launch("sandbox", "logs", "c9");
        assertEquals(2, member.exitCode());
        assertTrue(member.getErrorOutput().contains("Members: platform, c1, c2"), member.getErrorOutput());
    }
}

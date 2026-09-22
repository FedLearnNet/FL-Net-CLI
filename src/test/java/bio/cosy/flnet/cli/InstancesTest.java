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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusMainTest
class InstancesTest {

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

    @Test
    void secondClientNeedsANameAndGetsItsOwnPortAndProject(QuarkusMainLauncher launcher) throws IOException {
        assertEquals(0, launcher.launch("client", "init", "--no-input", "--network", "daibetes").exitCode());
        Map<String, String> first = EnvFileHelper.read(home.resolve("clients/default/.env"));
        assertEquals("fl-net-client", first.get("COMPOSE_PROJECT_NAME"));
        assertEquals("default", first.get("FLNET_INSTANCE_NAME"));

        LaunchResult unnamed = launcher.launch("client", "init", "--no-input", "--network", "daibetes");
        assertEquals(2, unnamed.exitCode());
        assertTrue(unnamed.getOutput().contains("already has 1 FL-Net Client instance"), unnamed.getOutput());
        assertTrue(unnamed.getErrorOutput().contains("Pass --name"), unnamed.getErrorOutput());

        LaunchResult named = launcher.launch("client", "init", "--no-input", "--network", "daibetes", "--name", "site-b");
        assertEquals(0, named.exitCode(), named.getErrorOutput());
        Map<String, String> second = EnvFileHelper.read(home.resolve("clients/site-b/.env"));
        assertEquals("fl-net-client-site-b", second.get("COMPOSE_PROJECT_NAME"));
        assertNotEquals(first.get("EXPOSED_PORT"), second.get("EXPOSED_PORT"));
        // container names follow the project, so both clients can run side by side
        String compose = Files.readString(home.resolve("clients/site-b/docker-compose.yml"));
        assertTrue(compose.contains("container_name: ${COMPOSE_PROJECT_NAME}-controller"), compose);
        assertTrue(named.getOutput().contains("flnet client up --name site-b"), named.getOutput());

        // an explicit port of another instance is refused, the failed instance leaves nothing behind
        LaunchResult conflict = launcher.launch("client", "init", "--no-input", "--network", "daibetes", "--name", "site-c",
                "--port", first.get("EXPOSED_PORT"));
        assertEquals(2, conflict.exitCode());
        assertTrue(conflict.getErrorOutput().contains("already used by client 'default'"), conflict.getErrorOutput());
        assertTrue(Files.notExists(home.resolve("clients/site-c/.env")));

        // a platform next to the clients does not reuse their ports
        Path cert = Files.writeString(home.resolve("fullchain.pem"), "cert");
        LaunchResult platform = launcher.launch("platform", "init", "--no-input", "--domain", "https://fl.example.org",
                "--bind-ip", "0.0.0.0", "--ssl-cert", cert.toString(), "--ssl-key", cert.toString());
        assertEquals(0, platform.exitCode(), platform.getErrorOutput());
        String nginxPort = EnvFileHelper.read(home.resolve("platforms/default/.env")).get("NGINX_PORT").replace("0.0.0.0:", "");
        assertNotEquals(first.get("EXPOSED_PORT"), nginxPort);
        assertNotEquals(second.get("EXPOSED_PORT"), nginxPort);
    }

    @Test
    void selectsListsAndShowsInstances(QuarkusMainLauncher launcher) {
        assertEquals(0, launcher.launch("client", "init", "--no-input", "--network", "daibetes").exitCode());

        // one instance: selected automatically, info shows details directly
        LaunchResult info = launcher.launch("client", "info");
        assertEquals(0, info.exitCode());
        assertTrue(info.getOutput().contains("FL-Net Client 'default'"), info.getOutput());
        assertTrue(info.getOutput().replaceAll(" +", " ").contains("Compose project fl-net-client"), info.getOutput());

        assertEquals(0, launcher.launch("client", "init", "--no-input", "--network", "microbaiome", "--name", "micro").exitCode());

        // several instances: info lists them, operations need --name
        LaunchResult list = launcher.launch("client", "info");
        assertEquals(0, list.exitCode());
        assertTrue(list.getOutput().contains("default") && list.getOutput().contains("micro"), list.getOutput());
        assertTrue(list.getOutput().contains("info --name <name>"), list.getOutput());

        LaunchResult ambiguous = launcher.launch("client", "status");
        assertEquals(2, ambiguous.exitCode());
        assertTrue(ambiguous.getErrorOutput().contains("default, micro"), ambiguous.getErrorOutput());

        LaunchResult unknown = launcher.launch("client", "logs", "--name", "nope");
        assertEquals(2, unknown.exitCode());
        assertTrue(unknown.getErrorOutput().contains("Existing: default, micro"), unknown.getErrorOutput());

        LaunchResult details = launcher.launch("client", "info", "--name", "micro");
        assertTrue(details.getOutput().contains("microb-ai-net.federated-learning.net"), details.getOutput());

        LaunchResult all = launcher.launch("list");
        assertEquals(0, all.exitCode());
        assertTrue(all.getOutput().contains("client     micro"), all.getOutput());

        LaunchResult badName = launcher.launch("client", "init", "--no-input", "--name", "Site_B");
        assertEquals(2, badName.exitCode());
    }

    @Test
    void networksComeFromConfiguration(QuarkusMainLauncher launcher) {
        LaunchResult help = launcher.launch("client", "init", "--help");
        assertTrue(help.getOutput().replaceAll("\\s+", " ").contains("Network to join: flnet, microbaiome, daibetes,"), help.getOutput());

        assertEquals(0, launcher.launch("client", "init", "--no-input", "--network", "microbaiome").exitCode());
        Map<String, String> env = EnvFileHelper.read(home.resolve("clients/default/.env"));
        assertEquals("microb-ai-net.federated-learning.net", env.get("GLOBAL_DOMAIN"));
        assertEquals("9154", env.get("GLOBAL_TCP_PORT"));
        assertEquals("ghcr.io/fedlearnnet/frontends/local-microbaiome:latest", env.get("FRONTEND_IMAGE"));
        assertEquals("false", env.get("GLOBAL_KEYCLOAK_ENABLED"));
    }
}

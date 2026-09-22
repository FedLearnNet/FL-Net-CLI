package bio.cosy.flnet.cli;

import bio.cosy.flnet.cli.helper.EnvFileHelper;
import io.quarkus.test.junit.main.Launch;
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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusMainTest
class FlnetCommandTest {

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
    @Launch({"--help"})
    void printsHelp(LaunchResult result) {
        assertTrue(result.getOutput().contains("flnet client init"));
        assertTrue(result.getOutput().contains("migrate"));
    }

    @Test
    void recognizesCurrentDeploymentRevision(QuarkusMainLauncher launcher) {
        Path directory = home.resolve("migration-client");
        LaunchResult init = launcher.launch("client", "init", "--no-input", "--dir", directory.toString(), "--network", "daibetes");
        assertEquals(0, init.exitCode(), init.getErrorOutput());
        assertTrue(Files.exists(directory.resolve(".flnet/migrations.json")));
        LaunchResult migration = launcher.launch("migrate", "--kind", "client", "--dir", directory.toString(), "--dry-run", "--no-input");
        assertEquals(0, migration.exitCode(), migration.getErrorOutput());
        assertTrue(migration.getOutput().contains("Already at the newest"));
    }

    @Test
    @Launch(value = {"client"}, exitCode = 2)
    void requiresSubcommand(LaunchResult result) {
        assertTrue(result.getErrorOutput().contains("Missing required subcommand"));
    }

    @Test
    void createsEveryToolType(QuarkusMainLauncher launcher, @TempDir Path dir) throws IOException {
        for (String type : List.of("analysis", "pre-processing", "post-processing", "evaluation", "self-learned",
                "data-transformation", "extractor", "export")) {
            Path target = dir.resolve(type);
            LaunchResult result = launcher.launch("tool", "create", "My " + type + " Tool", "--type", type, "--no-input",
                    "-o", target.toString());
            assertEquals(0, result.exitCode(), result.getErrorOutput());
            String app = Files.readString(target.resolve("app.py"));
            // main.py must import exactly the class app.py defines
            var imported = java.util.regex.Pattern.compile("from app import (\\w+)").matcher(Files.readString(target.resolve("main.py")));
            assertTrue(imported.find());
            assertTrue(app.contains("class " + imported.group(1) + "("), app);
            assertTrue(Files.readString(target.resolve("app.yml")).contains("type: " + type.toUpperCase().replace('-', '_')));
            assertEquals(!type.equals("data-transformation"), Files.exists(target.resolve("config.py")));
            assertTrue(Files.exists(target.resolve(".gitignore")));
            assertFalse(app.contains("{tool."), "unrendered template expression in " + type);
        }
    }

    @Test
    void createsFederatedToolAndRefusesNonEmptyTarget(QuarkusMainLauncher launcher, @TempDir Path dir) throws IOException {
        Path target = dir.resolve("fed");
        LaunchResult result = launcher.launch("tool", "create", "Fed Mean", "--federated", "--app-id", "42", "--no-input",
                "-o", target.toString());
        assertEquals(0, result.exitCode(), result.getErrorOutput());
        assertTrue(Files.readString(target.resolve("main.py")).contains("engine.register_federated(FedMean())"));
        assertTrue(Files.exists(target.resolve("aggregator.py")));
        assertTrue(Files.readString(target.resolve(".env")).contains("APP_ID=42"));

        LaunchResult again = launcher.launch("tool", "create", "Fed Mean", "--no-input", "-o", target.toString());
        assertEquals(2, again.exitCode());
        assertTrue(again.getErrorOutput().contains("--force"));

        LaunchResult wrongType = launcher.launch("tool", "create", "x", "--type", "export", "--federated", "--no-input",
                "-o", dir.resolve("x").toString());
        assertEquals(2, wrongType.exitCode());
    }

    @Test
    void initializesPlatformAndKeepsSecretsOnRerun(QuarkusMainLauncher launcher, @TempDir Path dir) throws IOException {
        Path target = dir.resolve("platform");
        Path cert = Files.writeString(dir.resolve("fullchain.pem"), "cert");
        Path key = Files.writeString(dir.resolve("privkey.pem"), "key");

        LaunchResult missingCert = launcher.launch("platform", "init", "--no-input", "--dir", target.toString());
        assertEquals(2, missingCert.exitCode());
        assertTrue(missingCert.getErrorOutput().contains("--ssl-cert"));
        assertFalse(Files.exists(target.resolve(".env")), "nothing may be written before all answers are known");

        String[] args = {"platform", "init", "--no-input", "--dir", target.toString(), "--domain", "https://fl.example.org",
                "--bind-ip", "0.0.0.0", "--ssl-cert", cert.toString(), "--ssl-key", key.toString(), "--no-client-auth",
                "--image-tag", "test-tag", "--frontend-image", "example/platform:custom"};
        assertEquals(0, launcher.launch(args).exitCode());
        Map<String, String> env = EnvFileHelper.read(target.resolve(".env"));
        assertEquals("https://fl.example.org", env.get("DEPLOYED_ON_DOMAIN"));
        assertEquals("0.0.0.0:8250", env.get("NGINX_PORT"));
        assertEquals("ssl", env.get("COMPOSE_PROFILES"));
        assertEquals("false", env.get("REQUIRE_CLIENT_AUTHENTICATION"));
        assertEquals("test-tag", env.get("IMAGE_TAG"));
        assertEquals("example/platform:custom", env.get("FRONTEND_IMAGE"));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(target.resolve(".env"))));

        Map<String, String> keycloak = EnvFileHelper.read(target.resolve("env/keycloak-secrets.env"));
        Map<String, String> learning = EnvFileHelper.read(target.resolve("env/global-learning-secrets.env"));
        assertEquals(learning.get("QUARKUS_OIDC_CREDENTIALS_SECRET"), keycloak.get("DATABASE_API_SECRET"));
        assertEquals(64, learning.get("POSTGRES_PASSWORD").length());

        assertEquals(0, launcher.launch(args).exitCode());
        assertEquals(keycloak, EnvFileHelper.read(target.resolve("env/keycloak-secrets.env")));
    }

    @Test
    void initializesAndReconfiguresClient(QuarkusMainLauncher launcher, @TempDir Path dir) throws IOException {
        Path target = dir.resolve("client");
        Path password = Files.writeString(dir.resolve("password"), "s3cret\n");

        LaunchResult result = launcher.launch("client", "init", "--no-input", "--dir", target.toString(),
                "--network", "flnet", "--platform-username", "alice", "--platform-password-file", password.toString(),
                "--domain", "https://flnet.hospital.org", "--listen", "localhost", "--port", "8250", "--image-tag", "test-tag");
        assertEquals(0, result.exitCode(), result.getErrorOutput());
        Map<String, String> env = EnvFileHelper.read(target.resolve(".env"));
        assertEquals("federated-learning.net", env.get("GLOBAL_DOMAIN"));
        assertEquals("9152", env.get("GLOBAL_TCP_PORT"));
        assertEquals("ghcr.io/fedlearnnet/frontends/local-fl-net:test-tag", env.get("FRONTEND_IMAGE"));
        assertEquals("https://flnet.hospital.org", env.get("DEPLOYED_ON_ADDRESS"));
        assertEquals("true", env.get("DISABLE_AUTOMATIC_COHORT_PERMISSION_LEARNING"));
        assertTrue(Files.readString(target.resolve("nginx.conf")).contains("server_name flnet.hospital.org;"));
        Map<String, String> secrets = EnvFileHelper.read(target.resolve("env/local-learning-secrets.env"));
        assertEquals("alice", secrets.get("FLNET_GLOBAL_AUTH_USERNAME"));
        assertEquals("s3cret", secrets.get("FLNET_GLOBAL_AUTH_PASSWORD"));

        // reconfigure: previous answers are defaults, secrets and the platform login are kept
        LaunchResult reconfigure = launcher.launch("client", "init", "--no-input", "--dir", target.toString(), "--port", "9000",
                "--frontend-image", "example/client:custom");
        assertEquals(0, reconfigure.exitCode(), reconfigure.getErrorOutput());
        assertEquals(secrets, EnvFileHelper.read(target.resolve("env/local-learning-secrets.env")));
        Map<String, String> reconfigured = EnvFileHelper.read(target.resolve(".env"));
        assertEquals("9000", reconfigured.get("EXPOSED_PORT"));
        assertEquals("example/client:custom", reconfigured.get("FRONTEND_IMAGE"));
        assertEquals("https://flnet.hospital.org", reconfigured.get("DEPLOYED_ON_ADDRESS"));

        // a clean start destroys data and needs an explicit confirmation
        LaunchResult clean = launcher.launch("client", "init", "--no-input", "--dir", target.toString(), "--mode", "clean");
        assertEquals(2, clean.exitCode());
        assertEquals(secrets, EnvFileHelper.read(target.resolve("env/local-learning-secrets.env")));
    }

    @Test
    void createsSelfSignedCertificateDuringInit(QuarkusMainLauncher launcher, @TempDir Path dir) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                bio.cosy.flnet.cli.helper.ProcessHelper.probe("openssl", "version").isPresent(), "openssl required");
        Path target = dir.resolve("client");
        LaunchResult result = launcher.launch("client", "init", "--no-input", "--dir", target.toString(), "--network", "daibetes",
                "--domain", "https://flnet.internal", "--ssl", "self-signed", "--ip", "10.0.0.5", "--days", "30");
        assertEquals(0, result.exitCode(), result.getErrorOutput());
        assertTrue(Files.exists(target.resolve("self_signed_certs/fullchain.pem")));
        String san = Files.readString(target.resolve("self_signed_certs/san.cnf"));
        assertTrue(san.contains("DNS.1 = flnet.internal") && san.contains("IP.1  = 10.0.0.5"), san);
        assertFalse(result.getOutput().contains("flnet client certs"), "the certificate exists, so no manual step is left");

        Path later = dir.resolve("later");
        LaunchResult skipped = launcher.launch("client", "init", "--no-input", "--dir", later.toString(), "--name", "later", "--network", "daibetes",
                "--domain", "https://flnet.internal", "--ssl", "self-signed", "--no-create-certificate");
        assertEquals(0, skipped.exitCode(), skipped.getErrorOutput());
        assertFalse(Files.exists(later.resolve("self_signed_certs/fullchain.pem")));
        assertTrue(skipped.getOutput().contains("flnet client certs"), skipped.getOutput());
    }

    @Test
    @Launch({"client", "init", "--help"})
    void initOffersCertificateOptions(LaunchResult result) {
        assertTrue(result.getOutput().contains("[no-]create-certificate") && result.getOutput().contains("--dns"), result.getOutput());
        assertTrue(result.getOutput().contains("--no-interactive"), result.getOutput());
    }

    @Test
    void everyCommandHasHelp(QuarkusMainLauncher launcher) {
        for (String command : List.of("client", "client init", "client certs", "client up", "client down", "client status",
                "client logs", "client compose", "platform", "platform init", "platform compose", "tool", "tool create", "doctor")) {
            List<String> args = new java.util.ArrayList<>(List.of(command.split(" ")));
            args.add("--help");
            LaunchResult result = launcher.launch(args.toArray(String[]::new));
            assertEquals(0, result.exitCode(), command + ": " + result.getErrorOutput());
            assertFalse(result.getOutput().contains("null references"), command);
        }
    }

    @Test
    void generatesStandaloneComposeFile(QuarkusMainLauncher launcher, @TempDir Path dir) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                bio.cosy.flnet.cli.helper.ProcessHelper.probe("docker", "compose", "version").isPresent(), "docker compose CLI required");
        Path target = dir.resolve("client");
        Path generated = target.resolve("docker-compose.generated.yml");

        // --compose on init writes <dir>/docker-compose.generated.yml with the secrets inlined, owner-only
        LaunchResult init = launcher.launch("client", "init", "--no-input", "--dir", target.toString(), "--network", "daibetes",
                "--compose");
        assertEquals(0, init.exitCode(), init.getErrorOutput());
        String resolved = Files.readString(generated);
        assertTrue(resolved.contains("name: fl-net-client"), resolved);
        String dbPassword = EnvFileHelper.read(target.resolve("env/orch-secrets.env")).get("POSTGRES_PASSWORD");
        assertTrue(resolved.contains(dbPassword));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(generated)));

        // --keep-variables: no secrets, variables and env files stay referenced
        Path shareable = dir.resolve("shareable.yml");
        LaunchResult keep = launcher.launch("client", "compose", "--dir", target.toString(), "-o", shareable.toString(),
                "--keep-variables");
        assertEquals(0, keep.exitCode(), keep.getErrorOutput());
        String kept = Files.readString(shareable);
        assertFalse(kept.contains(dbPassword));
        assertTrue(kept.contains("${"));
        assertTrue(kept.contains("orch-secrets.env"));
    }

    @Test
    void composeCommandsNeedAnInitializedDirectory(QuarkusMainLauncher launcher, @TempDir Path dir) {
        LaunchResult result = launcher.launch("client", "status", "--dir", dir.toString());
        assertEquals(3, result.exitCode());
        assertTrue(result.getErrorOutput().contains("flnet client init"));
    }
}

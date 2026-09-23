package bio.cosy.flnet.cli.base;

import bio.cosy.flnet.cli.base.deployment.*;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.EnvFileHelper;
import bio.cosy.flnet.cli.helper.NetworkHelper;
import bio.cosy.flnet.cli.helper.WebAddress;
import bio.cosy.flnet.cli.platform.config.PersistentPlatformConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static bio.cosy.flnet.cli.base.TestValidator.VALIDATOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FLNetDeploymentModelTest {

    @Test
    void modelAndPromptIpv4ValidationAgree() {
        FLNetClientDeployment client = new FLNetClientDeployment();
        FLNetPlatformDeployment platform = new FLNetPlatformDeployment();
        for (String ip : List.of("0.0.0.0", "127.0.0.1", "192.168.1.100", "255.255.255.255")) {
            assertTrue(NetworkHelper.isIpv4(ip), ip);
            assertTrue(VALIDATOR.validateValue(PersistentPlatformConfig.class, "bindIp", ip).isEmpty(), ip);
            client.setListen(ip);
            assertTrue(VALIDATOR.validateProperty(client, "bindIp").isEmpty(), ip);
        }
        for (String ip : List.of("1.2.3", "8250", "010.0.0.1", "256.1.1.1", "::1", "1.2.3.4.", "", "１.2.3.4")) {
            assertFalse(NetworkHelper.isIpv4(ip), ip);
            assertFalse(VALIDATOR.validateValue(PersistentPlatformConfig.class, "bindIp", ip).isEmpty(), ip);
            client.setListen(ip);
            assertFalse(VALIDATOR.validateProperty(client, "bindIp").isEmpty(), ip);
        }
        client.setListen("localhost");
        platform.setBindIp("localhost");
        assertTrue(VALIDATOR.validateProperty(client, "bindIp").isEmpty());
        assertTrue(VALIDATOR.validateProperty(platform, "config.bindIp").isEmpty());
    }

    @Test
    void portConstraintsKeepTheirBoundsAndMessages() {
        FLNetClientDeployment client = new FLNetClientDeployment();
        for (int port : new int[]{1, 65535}) {
            client.setPort(port);
            client.setPlatformRelayPort(port);
            assertTrue(VALIDATOR.validateProperty(client, "ports").isEmpty());
            assertTrue(VALIDATOR.validateProperty(client, "platformRelayPort").isEmpty());
        }
        for (int port : new int[]{0, 65536}) {
            client.setPort(port);
            client.setPlatformRelayPort(port);
            assertEquals("Ports must be between 1 and 65535.",
                    VALIDATOR.validateProperty(client, "ports").iterator().next().getMessage());
            assertEquals("The platform relay port must be between 1 and 65535.",
                    VALIDATOR.validateProperty(client, "platformRelayPort").iterator().next().getMessage());
        }
    }

    @Test
    void clientRoundTripsThroughItsFiles() {
        FLNetClientDeployment client = client();
        client.setDomain(WebAddress.parse("https://flnet.hospital.org"));
        client.useSsl(SslSource.SELF_SIGNED, null, null);
        client.setDefaultPermission(true);
        client.setAllowAutoLearning(true);
        client.setAutoLearning(AutoAccess.CERTIFIED_APPS);
        client.setFederation(false);
        assertTrue(client.problems(VALIDATOR).isEmpty(), client.problems(VALIDATOR).toString());

        FLNetClientDeployment loaded = new FLNetClientDeployment();
        loaded.setDirectory(client.getDirectory());
        loaded.fromEnv(asStrings(client.toEnv()));
        loaded.fromSecretFiles(client.toSecretFiles());

        assertEquals("site-a", loaded.getName());
        assertEquals("fl-net-client-site-a", loaded.getProjectName());
        assertEquals(client.getDomain(), loaded.getDomain());
        assertEquals(SslSource.SELF_SIGNED, loaded.getSslSource());
        assertEquals(AutoAccess.CERTIFIED_APPS, loaded.getAutoLearning());
        assertFalse(loaded.isFederation());
        assertEquals("", asStrings(loaded.toEnv()).get("GLOBAL_KEYCLOAK_URL"));
        assertEquals("federated-learning.invalid", asStrings(loaded.toEnv()).get("GLOBAL_FEDERATION_HOST"));
        assertEquals("alice", loaded.getPlatformUsername());
        assertEquals(client.toEnv().toString(), loaded.toEnv().toString());
        assertEquals(client.toSecretFiles(), loaded.toSecretFiles());
        assertDescribesExactlyItsEnv(client);
    }

    @Test
    void clientValidationCollectsAllProblems() {
        FLNetClientDeployment client = client();
        client.setName("Site_A");
        client.setListen("example.org");
        client.useSsl(SslSource.PROVIDED, Path.of("c.pem"), Path.of("k.pem"));
        client.setPlatformUsername(null);
        client.setAutoMetrics(AutoAccess.ALL);
        List<String> errors = client.problems(VALIDATOR);
        assertEquals(5, errors.size(), errors.toString());
    }

    @Test
    void clientWithoutPlatformAuthStoresPlaceholderLogin() {
        FLNetClientDeployment client = client();
        client.setPlatformAuth(false);
        client.setPlatformUsername(null);
        client.setPlatformPassword(null);
        assertTrue(client.problems(VALIDATOR).isEmpty(), client.problems(VALIDATOR).toString());
        assertEquals("dummy", client.toSecretFiles().get(FLNetClientDeployment.LEARNING_SECRETS).get("FLNET_GLOBAL_AUTH_USERNAME"));
    }

    @Test
    void legacyKeycloakPasswordFallbackIsClientOnly() {
        FLNetClientDeployment client = client();
        Map<String, Map<String, String>> files = client.toSecretFiles();
        Map<String, String> keycloak = files.get(BaseFLNetDeployableInstance.KEYCLOAK_SECRETS);
        String password = keycloak.remove("KC_DB_PASSWORD");
        keycloak.put("KC_BOOTSTRAP_ADMIN_USERNAME", " ");
        client.fromSecretFiles(files);
        assertEquals(password, client.getKeycloakDbPassword());
        assertEquals("keycloak-admin", client.getKeycloakAdminUsername());
        assertTrue(client.hasSecrets());

        FLNetPlatformDeployment platform = new FLNetPlatformDeployment();
        platform.setKeycloakAdminUsername("platform-admin");
        platform.fromSecretFiles(files);
        assertNull(platform.getKeycloakDbPassword());
        assertEquals("platform-admin", platform.getKeycloakAdminUsername());
        assertFalse(platform.hasSecrets());

        keycloak.put("KC_DB_PASSWORD", "current-password");
        client.fromSecretFiles(files);
        assertEquals("current-password", client.getKeycloakDbPassword());
        client.clearSecrets();
        assertFalse(client.hasSecrets());
        assertNull(client.getOrchDbPassword());
        assertNull(client.getKeycloakDbPassword());
        assertNull(client.getKeycloakAdminPassword());
    }

    @Test
    void platformRoundTripsAndChecksPorts() {
        FLNetPlatformDeployment platform = new FLNetPlatformDeployment();
        platform.setName("default");
        platform.setDirectory(Path.of("/tmp/flnet/platforms/default"));
        platform.setProjectName("fl-net-platform");
        platform.setImageTag("latest");
        platform.setFrontendImage("ghcr.io/fedlearnnet/frontends/global-fl-net:latest");
        platform.setKeycloakAdminUsername("admin");
        platform.setDomain(WebAddress.parse("https://fl.example.org"));
        platform.setBindIp("0.0.0.0");
        platform.setNginxPort(8250);
        platform.setRelayPort(9150);
        platform.setMinClients(3);
        platform.setSslEnabled(true);
        platform.setSslCertificate(Path.of("/etc/ssl/fullchain.pem"));
        platform.setSslPrivateKey(Path.of("/etc/ssl/privkey.pem"));
        platform.generateSecrets(64, 16);
        assertTrue(platform.problems(VALIDATOR).isEmpty(), platform.problems(VALIDATOR).toString());
        Map<String, String> keycloak = platform.toSecretFiles().get(FLNetPlatformDeployment.KEYCLOAK_SECRETS);
        assertEquals(platform.toSecretFiles().get(FLNetPlatformDeployment.GLOBAL_LEARNING_SECRETS).get("QUARKUS_OIDC_CREDENTIALS_SECRET"),
                keycloak.get("DATABASE_API_SECRET"));

        FLNetPlatformDeployment loaded = new FLNetPlatformDeployment();
        loaded.setDirectory(platform.getDirectory());
        loaded.fromEnv(asStrings(platform.toEnv()));
        loaded.fromSecretFiles(platform.toSecretFiles());
        assertEquals(platform.toEnv().toString(), loaded.toEnv().toString());
        assertEquals(platform.toSecretFiles(), loaded.toSecretFiles());
        assertDescribesExactlyItsEnv(platform);

        platform.setRelayPort(8250);
        platform.setSslEnabled(false);
        List<String> errors = platform.problems(VALIDATOR);
        assertTrue(errors.stream().anyMatch(e -> e.contains("only be used once")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("must terminate SSL")), errors.toString());
    }

    @Test
    void deploymentKindExposesItsBundleFolderAndDisplayName() {
        assertEquals("client", DeploymentKind.CLIENT.bundle());
        assertEquals("clients", DeploymentKind.CLIENT.folder());
        assertEquals("FL-Net Client", DeploymentKind.CLIENT.displayName());
        assertEquals("platform", DeploymentKind.PLATFORM.bundle());
        assertEquals("platforms", DeploymentKind.PLATFORM.folder());
        assertEquals("FL-Net Platform", DeploymentKind.PLATFORM.displayName());

        FLNetClientDeployment client = new FLNetClientDeployment();
        assertEquals(DeploymentKind.CLIENT, client.getKind());
        assertEquals("client", client.getTypeLabel());

        FLNetPlatformDeployment platform = new FLNetPlatformDeployment();
        assertEquals(DeploymentKind.PLATFORM, platform.getKind());
        assertEquals("platform", platform.getTypeLabel());
    }

    @Test
    void validateNameEnforcesTheNamePattern() {
        assertNotNull(BaseFLNetDeployableInstance.validateName(null));
        assertNotNull(BaseFLNetDeployableInstance.validateName(""));
        assertNotNull(BaseFLNetDeployableInstance.validateName("Site_A"));
        assertNotNull(BaseFLNetDeployableInstance.validateName("-site-a"));
        assertNull(BaseFLNetDeployableInstance.validateName("site-a"));
        assertNull(BaseFLNetDeployableInstance.validateName("a"));
    }

    @Test
    void isInitializedTracksTheEnvAndComposeFiles(@TempDir Path dir) throws IOException {
        FLNetClientDeployment client = new FLNetClientDeployment();
        client.setDirectory(dir);
        assertFalse(client.isInitialized());
        assertThrows(CliException.class, client::requireInitialized);

        Files.writeString(dir.resolve(".env"), "");
        assertFalse(client.isInitialized(), "the compose file is still missing");

        Files.writeString(dir.resolve("docker-compose.yml"), "");
        assertTrue(client.isInitialized());
        client.requireInitialized();
    }

    @Test
    void requireInitializedNamesTheKindAndDirectory(@TempDir Path dir) {
        FLNetPlatformDeployment platform = new FLNetPlatformDeployment();
        platform.setDirectory(dir);
        CliException error = assertThrows(CliException.class, platform::requireInitialized);
        assertEquals(CliException.ENVIRONMENT, error.exitCode());
        assertTrue(error.getMessage().contains("FL-Net Platform"), error.getMessage());
        assertTrue(error.getMessage().contains(dir.toString()), error.getMessage());
        assertTrue(error.getMessage().contains("flnet platform init"), error.getMessage());
    }

    @Test
    void missingCertificatesListsOnlyAbsentSslFiles(@TempDir Path dir) throws IOException {
        FLNetClientDeployment client = new FLNetClientDeployment();
        assertTrue(client.getMissingCertificates().isEmpty(), "SSL disabled: nothing is missing");

        Path certificate = dir.resolve("fullchain.pem");
        Path privateKey = dir.resolve("privkey.pem");
        client.setSslEnabled(true);
        client.setSslCertificate(certificate);
        client.setSslPrivateKey(privateKey);
        assertEquals(List.of(certificate, privateKey), client.getMissingCertificates());

        Files.writeString(certificate, "cert");
        assertEquals(List.of(privateKey), client.getMissingCertificates());

        Files.writeString(privateKey, "key");
        assertTrue(client.getMissingCertificates().isEmpty());
    }

    @Test
    void sslFilesMustBothBeSetWhenSslIsEnabled() {
        FLNetPlatformDeployment platform = platform();
        platform.setSslCertificate(Path.of("/etc/ssl/fullchain.pem"));
        platform.setSslPrivateKey(null);
        assertTrue(platform.problems(VALIDATOR).stream()
                .anyMatch(e -> e.contains("certificate or private key is not set")), platform.problems(VALIDATOR).toString());

        platform.setSslPrivateKey(Path.of("/etc/ssl/privkey.pem"));
        assertTrue(platform.problems(VALIDATOR).stream()
                .noneMatch(e -> e.contains("certificate or private key is not set")), platform.problems(VALIDATOR).toString());
    }

    @Test
    void certificateIsRequiredForThePlatformRelay() {
        FLNetPlatformDeployment platform = platform();
        platform.setSslCertificate(null);
        platform.setSslPrivateKey(null);
        assertTrue(platform.problems(VALIDATOR).stream()
                .anyMatch(e -> e.contains("CA-signed certificate")), platform.problems(VALIDATOR).toString());
    }

    @Test
    void secretFileNamesMatchTheWrittenFiles() {
        FLNetClientDeployment client = client();
        assertEquals(client.toSecretFiles().keySet(), client.getSecretFileNames());
        assertEquals(Set.of(BaseFLNetDeployableInstance.ORCH_SECRETS, FLNetClientDeployment.LEARNING_SECRETS,
                BaseFLNetDeployableInstance.KEYCLOAK_SECRETS), client.getSecretFileNames());

        FLNetPlatformDeployment platform = platform();
        assertEquals(platform.toSecretFiles().keySet(), platform.getSecretFileNames());
        assertEquals(Set.of(FLNetPlatformDeployment.DATAMODELER_SECRETS, FLNetPlatformDeployment.GLOBAL_LEARNING_SECRETS,
                BaseFLNetDeployableInstance.ORCH_SECRETS, BaseFLNetDeployableInstance.KEYCLOAK_SECRETS), platform.getSecretFileNames());
    }

    @Test
    void loadReadsBackWhatWasWrittenToDisk(@TempDir Path dir) {
        FLNetClientDeployment client = client();
        client.setDirectory(dir);

        EnvFileHelper.write(client.getEnvFile(), client.toEnv(), client.envComments());
        client.toSecretFiles().forEach((file, variables) -> EnvFileHelper.write(client.getSecretsDirectory().resolve(file), variables));

        FLNetClientDeployment loaded = new FLNetClientDeployment();
        loaded.setDirectory(dir);
        // keycloakRealmPath is a bundle-level default applied by the BO's create() before load(),
        // not part of the persisted .env, so the caller must set it before loading, same as production.
        loaded.setKeycloakRealmPath(client.getKeycloakRealmPath());
        loaded.load();

        assertEquals(client.getName(), loaded.getName());
        assertEquals(client.getPlatformUsername(), loaded.getPlatformUsername());
        assertEquals(client.toEnv().toString(), loaded.toEnv().toString());
        assertEquals(client.toSecretFiles(), loaded.toSecretFiles());
    }

    private static void assertDescribesExactlyItsEnv(BaseFLNetDeployableInstance instance) {
        assertEquals(new java.util.TreeSet<>(instance.toEnv().keySet()),
                new java.util.TreeSet<>(instance.envComments().keySet()));
        assertTrue(instance.envComments().values().stream().noneMatch(String::isBlank));
    }

    private static FLNetClientDeployment client() {
        FLNetClientDeployment client = new FLNetClientDeployment();
        client.setName("site-a");
        client.setDirectory(Path.of("/tmp/flnet/clients/site-a"));
        client.setProjectName("fl-net-client-site-a");
        client.setFrontendImage("ghcr.io/fedlearnnet/frontends/local-fl-net:latest");
        client.setKeycloakAdminUsername("keycloak-admin");
        client.setKeycloakRealmPath("/auth/realms/FLNet-Platform");
        client.setPlatformAddress(WebAddress.parse("https://federated-learning.net"));
        client.setPlatformRelayPort(9152);
        client.setPlatformUsername("alice");
        client.setPlatformPassword("secret");
        client.setPort(8250);
        client.setQueryRetryTime(3);
        client.setQuerySampleThreshold(100);
        client.generateSecrets(64, 16);
        return client;
    }

    private static FLNetPlatformDeployment platform() {
        FLNetPlatformDeployment platform = new FLNetPlatformDeployment();
        platform.setName("default");
        platform.setDirectory(Path.of("/tmp/flnet/platforms/default"));
        platform.setProjectName("fl-net-platform");
        platform.setImageTag("latest");
        platform.setFrontendImage("ghcr.io/fedlearnnet/frontends/global-fl-net:latest");
        platform.setKeycloakAdminUsername("admin");
        platform.setDomain(WebAddress.parse("https://fl.example.org"));
        platform.setBindIp("0.0.0.0");
        platform.setNginxPort(8250);
        platform.setRelayPort(9150);
        platform.setMinClients(3);
        platform.setSslEnabled(true);
        platform.setSslCertificate(Path.of("/etc/ssl/fullchain.pem"));
        platform.setSslPrivateKey(Path.of("/etc/ssl/privkey.pem"));
        platform.generateSecrets(64, 16);
        return platform;
    }

    private static Map<String, String> asStrings(Map<String, Object> env) {
        Map<String, String> strings = new HashMap<>();
        env.forEach((key, value) -> strings.put(key, value == null ? "" : String.valueOf(value)));
        return strings;
    }
}

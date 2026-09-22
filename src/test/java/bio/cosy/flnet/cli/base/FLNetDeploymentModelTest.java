package bio.cosy.flnet.cli.base;

import bio.cosy.flnet.cli.base.deployment.*;
import bio.cosy.flnet.cli.helper.NetworkHelper;
import bio.cosy.flnet.cli.helper.WebAddress;
import bio.cosy.flnet.cli.platform.config.PersistentPlatformConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static bio.cosy.flnet.cli.base.TestValidator.VALIDATOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private static Map<String, String> asStrings(Map<String, Object> env) {
        Map<String, String> strings = new HashMap<>();
        env.forEach((key, value) -> strings.put(key, value == null ? "" : String.valueOf(value)));
        return strings;
    }
}

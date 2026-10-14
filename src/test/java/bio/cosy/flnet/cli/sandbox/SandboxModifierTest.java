package bio.cosy.flnet.cli.sandbox;

import bio.cosy.flnet.cli.sandbox.SandboxModifier.ComposeOverride;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxModifierTest {

    @TempDir
    Path dir;

    @Test
    void replacesTextThatOccursExactlyOnce() throws IOException {
        Path file = Files.writeString(dir.resolve("nginx.conf"), "a {\n    deny;\n}\nb {\n    allow;\n}\n");
        SandboxModifier.findAndReplace(file, "    deny;\n", "    # deny;\n");
        assertEquals("a {\n    # deny;\n}\nb {\n    allow;\n}\n", Files.readString(file));
    }

    @Test
    void failsWhenTheBundleNoLongerMatches() throws IOException {
        Path file = Files.writeString(dir.resolve("nginx.conf"), "x;\ny;\nx;\n");
        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> SandboxModifier.findAndReplace(file, "z;", ""));
        assertTrue(missing.getMessage().contains("found none"), missing.getMessage());
        IllegalStateException several = assertThrows(IllegalStateException.class, () -> SandboxModifier.findAndReplace(file, "x;", ""));
        assertTrue(several.getMessage().contains("found several"), several.getMessage());
        assertEquals("x;\ny;\nx;\n", Files.readString(file));
    }

    @Test
    void writesAComposeOverride() throws IOException {
        ComposeOverride override = new ComposeOverride();
        override.service("relay-server").env("RELAY_MODE", "dev").resetVolumes();
        override.service("controller").image("controller:dev").extraHost("host.docker.internal:host-gateway")
                .env("ORIGINS", "${DEPLOYED_ON_DOMAIN},http://host.docker.internal:8250");
        SandboxModifier.writeComposeOverride(dir, override);
        String yaml = Files.readString(dir.resolve("docker-compose.override.yml"));
        assertTrue(yaml.endsWith("""
                services:
                  relay-server:
                    environment:
                      RELAY_MODE: "dev"
                    volumes: !reset []
                  controller:
                    image: "controller:dev"
                    environment:
                      ORIGINS: "${DEPLOYED_ON_DOMAIN},http://host.docker.internal:8250"
                    extra_hosts:
                      - "host.docker.internal:host-gateway"
                """), yaml);
    }

    @Test
    void patchesARealm() throws IOException {
        Path realm = Files.writeString(dir.resolve("realm.json"), """
                {"users": [{"username": "test", "old": true}, {"username": "service"}],
                 "clients": [{"clientId": "frontend", "directAccessGrantsEnabled": false, "redirectUris": ["a"]}]}
                """);
        SandboxModifier.patchRealm(realm, root -> {
            SandboxModifier.addUser(root, "{\"username\": \"test\", \"enabled\": true}");
            SandboxModifier.addRedirectUri(root, "frontend", "localhost:8250/*");
            SandboxModifier.addRedirectUri(root, "frontend", "localhost:8250/*");
            SandboxModifier.client(root, "frontend").put("directAccessGrantsEnabled", true);
        });
        String patched = Files.readString(realm).replaceAll("\\s", "");
        assertTrue(patched.contains("\"users\":[{\"username\":\"test\",\"enabled\":true},{\"username\":\"service\"}]"), patched);
        assertTrue(patched.contains("\"directAccessGrantsEnabled\":true,\"redirectUris\":[\"a\",\"localhost:8250/*\"]"), patched);
        assertThrows(IllegalStateException.class, () -> SandboxModifier.patchRealm(realm, root -> SandboxModifier.client(root, "missing")));
    }
}

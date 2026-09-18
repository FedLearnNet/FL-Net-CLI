package bio.cosy.flnet.cli.deploy;

import bio.cosy.flnet.cli.base.DeploymentKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundleTest {

    @Test
    void neverShipsSecretsOrLocalData() {
        assertFalse(Bundle.isDeploymentFile(".env"));
        assertFalse(Bundle.isDeploymentFile("env/orch-secrets.env"));
        assertFalse(Bundle.isDeploymentFile("self_signed_certs/privkey.pem"));
        assertFalse(Bundle.isDeploymentFile("self_signed_certs/san.cnf"));
        assertFalse(Bundle.isDeploymentFile("umls/MRCONSO.RRF"));
        assertTrue(Bundle.isDeploymentFile("umls/.gitignore"));
        assertTrue(Bundle.isDeploymentFile("self_signed_certs/san.cnf.template"));
        assertTrue(Bundle.isDeploymentFile("docker-compose.yml"));
    }

    @Test
    void embeddedBundlesContainComposeFileAndNoSecrets() throws IOException {
        for (DeploymentKind kind : DeploymentKind.values()) {
            List<String> files = Bundle.files(kind, null);
            assertTrue(files.contains("docker-compose.yml"), kind + ": " + files);
            assertTrue(files.contains("nginx.conf"), kind + ": " + files);
            assertTrue(files.stream().allMatch(Bundle::isDeploymentFile), kind + ": " + files);
        }
    }

    @Test
    void keepsExistingFilesUnlessOverwriting(@TempDir Path dir) throws IOException {
        Bundle.install(DeploymentKind.CLIENT, dir, null, false);
        Path compose = dir.resolve("docker-compose.yml");
        Files.writeString(compose, "# local change");

        Bundle.InstallResult kept = Bundle.install(DeploymentKind.CLIENT, dir, null, false);
        assertTrue(kept.getKept().contains("docker-compose.yml"));
        assertEquals("# local change", Files.readString(compose));

        Bundle.install(DeploymentKind.CLIENT, dir, null, true);
        assertTrue(Files.readString(compose).contains("services"));
        assertTrue(Files.isExecutable(dir.resolve("create-backup.sh")));
    }
}

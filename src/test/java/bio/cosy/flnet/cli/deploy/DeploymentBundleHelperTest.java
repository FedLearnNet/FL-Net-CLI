package bio.cosy.flnet.cli.deploy;

import bio.cosy.flnet.cli.base.deployment.DeploymentKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentBundleHelperTest {

    @Test
    void neverShipsSecretsOrLocalData() {
        assertFalse(DeploymentBundleHelper.isDeploymentFile(".env"));
        assertFalse(DeploymentBundleHelper.isDeploymentFile("env/orch-secrets.env"));
        assertFalse(DeploymentBundleHelper.isDeploymentFile(".flnet/migrations.json"));
        assertFalse(DeploymentBundleHelper.isDeploymentFile(".flnet/backups/transaction/env/orch-secrets.env"));
        assertFalse(DeploymentBundleHelper.isDeploymentFile("self_signed_certs/privkey.pem"));
        assertFalse(DeploymentBundleHelper.isDeploymentFile("self_signed_certs/san.cnf"));
        assertFalse(DeploymentBundleHelper.isDeploymentFile("umls/MRCONSO.RRF"));
        assertTrue(DeploymentBundleHelper.isDeploymentFile("umls/.gitignore"));
        assertTrue(DeploymentBundleHelper.isDeploymentFile("self_signed_certs/san.cnf.template"));
        assertTrue(DeploymentBundleHelper.isDeploymentFile("docker-compose.yml"));
    }

    @Test
    void embeddedBundlesContainComposeFileAndNoSecrets() throws IOException {
        for (DeploymentKind kind : DeploymentKind.values()) {
            List<String> files = DeploymentBundleHelper.files(kind, null);
            assertTrue(files.contains("docker-compose.yml"), kind + ": " + files);
            assertTrue(files.contains("nginx.conf"), kind + ": " + files);
            assertTrue(files.stream().allMatch(DeploymentBundleHelper::isDeploymentFile), kind + ": " + files);
        }
    }

    @Test
    void keepsExistingFilesUnlessOverwriting(@TempDir Path dir) throws IOException {
        DeploymentBundleHelper.install(DeploymentKind.CLIENT, dir, null, false);
        Path compose = dir.resolve("docker-compose.yml");
        Files.writeString(compose, "# local change");

        DeploymentBundleHelper.InstallResult kept = DeploymentBundleHelper.install(DeploymentKind.CLIENT, dir, null, false);
        assertTrue(kept.getKept().contains("docker-compose.yml"));
        assertEquals("# local change", Files.readString(compose));

        DeploymentBundleHelper.install(DeploymentKind.CLIENT, dir, null, true);
        assertTrue(Files.readString(compose).contains("services"));
        assertTrue(Files.isExecutable(dir.resolve("create-backup.sh")));
    }
}

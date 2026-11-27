package bio.cosy.flnet.cli.deploy.backup;

import bio.cosy.flnet.cli.base.deployment.FLNetClientDeployment;
import bio.cosy.flnet.cli.helper.CliException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreBOTest {

    @TempDir
    Path directory;

    private final ObjectMapper objectMapper = new JsonMapper();
    private RestoreBO restoreBO;
    private FLNetClientDeployment client;

    @BeforeEach
    void setUp() throws IOException {
        restoreBO = new RestoreBO();
        restoreBO.objectMapper = objectMapper;
        Path deployment = Files.createDirectories(directory.resolve("client"));
        Files.writeString(deployment.resolve(".env"), "IMAGE_TAG=1.4.0\n");
        Files.writeString(deployment.resolve("docker-compose.yml"), "services: {}\n");
        client = new FLNetClientDeployment();
        client.setDirectory(deployment);
    }

    @Test
    void neverRestoresAnIncompleteBackup() throws IOException {
        Path backup = Files.createDirectories(restoreBO.backupsDir(client).resolve("broken"));
        DeploymentBackup manifest = new DeploymentBackup();
        manifest.setName("broken");
        manifest.setDatabases(List.of());
        objectMapper.writeValue(backup.resolve(DeploymentBackup.MANIFEST).toFile(), manifest);

        CliException error = assertThrows(CliException.class, () -> restoreBO.restore(client, "broken"));

        assertTrue(error.getMessage().contains("incomplete"));
        assertEquals("services: {}\n", Files.readString(client.getComposeFile()));
    }

    @Test
    void neverRestoresDatabasesOfANewerVersion() throws IOException {
        client.useImageTag("1.4.0");
        Path backup = Files.createDirectories(restoreBO.backupsDir(client).resolve("newer"));
        DeploymentBackup manifest = new DeploymentBackup();
        manifest.setName("newer");
        manifest.setVersion("1.5.0");
        manifest.setComplete(true);
        objectMapper.writeValue(backup.resolve(DeploymentBackup.MANIFEST).toFile(), manifest);

        CliException error = assertThrows(CliException.class, () -> restoreBO.restore(client, "newer"));

        assertTrue(error.getMessage().contains("Upgrade to 1.5.0 first"));
    }

    @Test
    void rejectsNamesThatLeaveTheBackupFolder() {
        assertThrows(CliException.class, () -> restoreBO.restore(client, "../client"));
        assertThrows(CliException.class, () -> restoreBO.restore(client, ""));
    }
}

package bio.cosy.flnet.cli.deploy.backup;

import bio.cosy.flnet.cli.base.deployment.FLNetClientDeployment;
import bio.cosy.flnet.cli.helper.DirectoryHelper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupBOTest {

    @TempDir
    Path directory;

    private final ObjectMapper objectMapper = new JsonMapper();
    private BackupBO backupBO;
    private FLNetClientDeployment client;

    @BeforeEach
    void setUp() throws IOException {
        backupBO = new BackupBO();
        backupBO.objectMapper = objectMapper;
        Path deployment = Files.createDirectories(directory.resolve("client"));
        Files.writeString(deployment.resolve(".env"), "IMAGE_TAG=1.4.0\n");
        Files.writeString(deployment.resolve("docker-compose.yml"), "services: {}\n");
        client = new FLNetClientDeployment();
        client.setDirectory(deployment);
    }

    @Test
    void findsPostgresServicesInTheComposeConfig() throws IOException {
        String config = """
                {"services": {
                  "local-learning-api-db": {"environment": {"POSTGRES_DB": "llm", "POSTGRES_USER": "user"}},
                  "keycloak-postgres": {"environment": ["POSTGRES_DB=keycloak", "POSTGRES_USER=keycloak"]},
                  "local-learning-api": {"environment": {"QUARKUS_DATASOURCE_USERNAME": "user"}},
                  "nginx": {}
                }}""";

        List<DatabaseDump> databases = BackupBO.databasesOf(objectMapper.readTree(config));

        assertEquals(List.of(
                new DatabaseDump("local-learning-api-db", "user", "llm", "local-learning-api-db.sql"),
                new DatabaseDump("keycloak-postgres", "keycloak", "keycloak", "keycloak-postgres.sql")), databases);
    }

    @Test
    void removesOnlyIncompleteBackups() throws IOException {
        writeBackup("broken", false);
        writeBackup("good", true);
        // a folder of create-backup.sh has no manifest and is left alone
        Files.createDirectories(backupBO.backupsDir(client).resolve("backup_2026-01-01_00-00-00"));

        backupBO.removeIncomplete(client);

        assertFalse(Files.exists(backupBO.backupsDir(client).resolve("broken")));
        assertTrue(Files.exists(backupBO.backupsDir(client).resolve("good")));
        assertTrue(Files.exists(backupBO.backupsDir(client).resolve("backup_2026-01-01_00-00-00")));
    }

    @Test
    void manifestRoundTrip() throws IOException {
        writeBackup("good", true);

        DeploymentBackup loaded = backupBO.load(backupBO.backupsDir(client).resolve("good"));

        assertEquals("good", loaded.getName());
        assertEquals("1.4.0", loaded.getVersion());
        assertTrue(loaded.isComplete());
        assertEquals(List.of(new DatabaseDump("db", "user", "llm", "db.sql")), loaded.getDatabases());
    }

    @Test
    void copyLeavesOutTheSkippedFolders() throws IOException {
        Files.createDirectories(client.resolve("backups/old"));
        Files.writeString(client.resolve("backups/old/secret.sql"), "dump");
        Files.createDirectories(client.resolve("env"));
        Files.writeString(client.resolve("env/secrets.env"), "PASSWORD=x\n");
        Files.createDirectories(client.resolve("updates"));
        Files.writeString(client.resolve("updates/state.json"), "{}");
        Path target = directory.resolve("copy");

        DirectoryHelper.copy(client.getDirectory(), target, List.of(BaseBackupBO.DIRECTORY, BaseBackupBO.UPDATES_DIRECTORY));

        assertEquals("PASSWORD=x\n", Files.readString(target.resolve("env/secrets.env")));
        assertTrue(Files.exists(target.resolve(".env")));
        assertFalse(Files.exists(target.resolve("backups")));
        assertFalse(Files.exists(target.resolve("updates")));
    }

    private void writeBackup(String name, boolean complete) throws IOException {
        Path backup = Files.createDirectories(backupBO.backupsDir(client).resolve(name));
        DeploymentBackup manifest = new DeploymentBackup();
        manifest.setName(name);
        manifest.setCreatedAt(Date.from(Instant.parse("2026-11-25T10:00:00Z")));
        manifest.setVersion("1.4.0");
        manifest.setDatabases(List.of(new DatabaseDump("db", "user", "llm", "db.sql")));
        manifest.setComplete(complete);
        objectMapper.writeValue(backup.resolve(DeploymentBackup.MANIFEST).toFile(), manifest);
    }
}

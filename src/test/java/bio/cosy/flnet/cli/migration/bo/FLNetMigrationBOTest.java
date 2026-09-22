package bio.cosy.flnet.cli.migration.bo;

import bio.cosy.flnet.cli.base.deployment.DeploymentKind;
import bio.cosy.flnet.cli.migration.MigrationCatalog;
import bio.cosy.flnet.cli.migration.MigrationCatalog.Definition;
import bio.cosy.flnet.cli.migration.MigrationCatalog.Task;
import bio.cosy.flnet.cli.migration.MigrationPlan;
import bio.cosy.flnet.cli.migration.MigrationState;
import bio.cosy.flnet.cli.helper.CliException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static bio.cosy.flnet.cli.migration.MigrationFileHelper.STATE;
import static org.junit.jupiter.api.Assertions.*;

public class FLNetMigrationBOTest {
    @TempDir Path directory;
    MigrationCatalog catalog;
    FLNetMigrationBO bo;

    @BeforeEach
    void setup() throws IOException {
        Files.writeString(directory.resolve("docker-compose.yml"), "services: {}\n");
        Files.writeString(directory.resolve(".env"), "# comment\nOLD_KEY='user value'\nIMAGE=example/app:1\nCUSTOM=yes\n");
        catalog = new MigrationCatalog();
        bo = new FLNetMigrationBO() { @Override protected MigrationCatalog catalog() { return catalog; } };
        bo.mapper = new ObjectMapper();
    }

    @Test
    void previewsThenAppliesAllTasksAndSkipsCompletedRevisions() throws Exception {
        Task rename = op(Task.Type.ENV_RENAME, ".env"); rename.setKey("OLD_KEY"); rename.setNewKey("NEW_KEY");
        Task image = op(Task.Type.ENV_SET, ".env"); image.setKey("IMAGE"); image.setExpectedValue("example/app:1"); image.setValue("example/app:2");
        catalog.setMigrations(List.of(step(2, rename), step(3, image)));
        byte[] original = Files.readAllBytes(directory.resolve(".env"));
        MigrationPlan plan = bo.plan(DeploymentKind.CLIENT, directory, 1);
        assertEquals(1, plan.getCurrentRevision()); assertEquals(3, plan.getTargetRevision());
        assertArrayEquals(original, Files.readAllBytes(directory.resolve(".env")));
        assertFalse(Files.exists(directory.resolve(".flnet")));
        bo.migrateToNewest(plan);
        assertEquals("# comment\nNEW_KEY='user value'\nIMAGE=example/app:2\nCUSTOM=yes\n", Files.readString(directory.resolve(".env")));
        assertEquals(3, bo.mapper.readValue(directory.resolve(STATE).toFile(), MigrationState.class).getRevision());
        assertTrue(bo.plan(DeploymentKind.CLIENT, directory, null).getMigrations().isEmpty());
        assertFalse(Files.exists(directory.resolve(".flnet/backups")));
    }

    @Test
    void requiresExplicitVersionForLegacyInstallationsAndRejectsGaps() {
        assertThrows(CliException.class, () -> bo.plan(DeploymentKind.CLIENT, directory, null));
        Task add = op(Task.Type.ENV_ADD, ".env"); add.setKey("NEW"); add.setValue("yes");
        catalog.setMigrations(List.of(step(3, add)));
        assertThrows(CliException.class, () -> bo.plan(DeploymentKind.CLIENT, directory, 1));
        catalog.setMigrations(List.of(step(2, add), step(2, add)));
        assertThrows(CliException.class, () -> bo.plan(DeploymentKind.CLIENT, directory, 1));
    }

    @Test
    void refusesConflictingValuesAndEditsAfterPreviewWithoutWriting() throws Exception {
        Task edit = op(Task.Type.ENV_SET, ".env"); edit.setKey("IMAGE"); edit.setExpectedValue("wrong"); edit.setValue("example/app:2");
        catalog.setMigrations(List.of(step(2, edit)));
        assertThrows(CliException.class, () -> bo.plan(DeploymentKind.CLIENT, directory, 1));
        edit.setExpectedValue("example/app:1");
        MigrationPlan plan = bo.plan(DeploymentKind.CLIENT, directory, 1);
        Files.writeString(directory.resolve(".env"), "CUSTOM=edited after preview\n");
        assertThrows(CliException.class, () -> bo.migrateToNewest(plan));
        assertEquals("CUSTOM=edited after preview\n", Files.readString(directory.resolve(".env")));
        assertFalse(Files.exists(directory.resolve(STATE)));
    }

    @Test
    void addsReplacesRemovesAndMergesFiles() throws Exception {
        Files.writeString(directory.resolve("old.conf"), "obsolete\n");
        Task add = op(Task.Type.FILE_ADD, "new.conf"); add.setValue("feature=true\n");
        Task replace = op(Task.Type.FILE_REPLACE, "old.conf"); replace.setExpectedValue("obsolete\n"); replace.setValue("updated\n");
        Task remove = op(Task.Type.FILE_REMOVE, "old.conf"); remove.setExpectedValue("updated\n");
        Task merge = op(Task.Type.TEXT_REPLACE, "docker-compose.yml"); merge.setExpectedValue("services: {}"); merge.setValue("services:\n  web:\n    image: example/web:2");
        catalog.setMigrations(List.of(step(2, add, replace, remove, merge)));
        bo.migrateToNewest(bo.plan(DeploymentKind.CLIENT, directory, 1));
        assertEquals("feature=true\n", Files.readString(directory.resolve("new.conf")));
        assertFalse(Files.exists(directory.resolve("old.conf")));
        assertTrue(Files.readString(directory.resolve("docker-compose.yml")).contains("image: example/web:2"));
    }

    @Test
    void refusesEscapingAndSymlinkPaths() throws Exception {
        Task task = op(Task.Type.FILE_ADD, "../outside"); task.setValue("text"); catalog.setMigrations(List.of(step(2, task)));
        assertThrows(CliException.class, () -> bo.plan(DeploymentKind.CLIENT, directory, 1));
        task.setPath(".flnet/migrations.json");
        assertThrows(CliException.class, () -> bo.plan(DeploymentKind.CLIENT, directory, 1));
        Files.createSymbolicLink(directory.resolve("linked.conf"), directory.resolve(".env")); task.setPath("linked.conf");
        assertThrows(CliException.class, () -> bo.plan(DeploymentKind.CLIENT, directory, 1));
    }

    @Test
    void failedWriteLeavesEarlierChangesButDoesNotAdvanceVersion() throws Exception {
        Task add = op(Task.Type.FILE_ADD, "new.conf"); add.setValue("applied\n");
        Task blocked = op(Task.Type.FILE_ADD, "blocked/child.conf"); blocked.setValue("unwritten\n");
        Files.writeString(directory.resolve("blocked"), "a file"); catalog.setMigrations(List.of(step(2, add, blocked)));
        MigrationPlan plan = bo.plan(DeploymentKind.CLIENT, directory, 1);
        IOException failure = assertThrows(IOException.class, () -> bo.migrateToNewest(plan));
        assertTrue(failure.getMessage().contains("Some files may already have changed"));
        assertEquals("applied\n", Files.readString(directory.resolve("new.conf")));
        assertFalse(Files.exists(directory.resolve(STATE)));
        assertFalse(Files.exists(directory.resolve(".flnet/backups")));
    }

    @Test
    void readsEarlierStateFormatAndRejectsWrongVersionOrKind() throws Exception {
        Files.createDirectories(directory.resolve(".flnet"));
        Files.writeString(directory.resolve(STATE), "{\"formatVersion\":1,\"kind\":\"CLIENT\",\"revision\":1,\"applied\":{}}");
        assertTrue(bo.plan(DeploymentKind.CLIENT, directory, null).getMigrations().isEmpty());
        assertThrows(CliException.class, () -> bo.plan(DeploymentKind.CLIENT, directory, 2));
        assertThrows(CliException.class, () -> bo.plan(DeploymentKind.PLATFORM, directory, null));
    }

    public static Task op(Task.Type type, String path) {
        Task task = new Task(); task.setType(type); task.setPath(path); task.setDescription("Test change"); return task;
    }
    public static Definition step(int revision, Task... tasks) {
        Definition result = new Definition(); result.setKind(DeploymentKind.CLIENT); result.setRevision(revision);
        result.setDescription("Upgrade test configuration"); result.setTasks(List.of(tasks)); return result;
    }
}

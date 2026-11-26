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
import static org.junit.jupiter.api.Assertions.assertTrue;

// the refusals of 'flnet upgrade'; they come before anything touches docker
@QuarkusMainTest
class UpgradeTest {

    @TempDir
    Path home;

    private Path client;

    @BeforeEach
    void initClient(QuarkusMainLauncher launcher) throws IOException {
        System.setProperty("flnet.home", home.toString());
        Path password = Files.writeString(home.resolve("platform-password"), "s3cret\n");
        client = home.resolve("client");
        LaunchResult init = launcher.launch("client", "init", "--no-input", "--dir", client.toString(), "--network", "flnet",
                "--platform-username", "alice", "--platform-password-file", password.toString());
        assertEquals(0, init.exitCode(), init.getErrorOutput());
    }

    @AfterEach
    void resetHome() {
        System.clearProperty("flnet.home");
    }

    @Test
    void initRecordsWhereAndWhatTheDeploymentIs() {
        Map<String, String> env = EnvFileHelper.read(client.resolve(".env"));
        assertEquals("client", env.get("FLNET_DEPLOYMENT_KIND"));
        assertEquals(client.toString(), env.get("FLNET_DEPLOYMENT_DIR"));
    }

    @Test
    void initCreatesTheFoldersOrchApiMounts() {
        // else docker creates them owned by root
        assertTrue(Files.isDirectory(client.resolve("backups")));
        assertTrue(Files.isDirectory(client.resolve("updates")));
    }

    @Test
    void aFailedBackupLeavesItsReasonForTheAdminPage(QuarkusMainLauncher launcher) throws IOException {
        // the deployment does not run, so nothing can be dumped
        LaunchResult backup = launcher.launch("backup", "failed", "--dir", client.toString());

        assertTrue(backup.exitCode() != 0, backup.getOutput());
        String manifest = Files.readString(client.resolve("backups/failed/backup.json"));
        assertTrue(manifest.contains("\"complete\" : false"), manifest);
        assertTrue(manifest.contains("\"message\" : \""), manifest);
        assertTrue(Files.notExists(client.resolve("backups/failed/db")));
    }

    @Test
    void restoreNeedsAnExistingBackup(QuarkusMainLauncher launcher) {
        LaunchResult restore = launcher.launch("restore", "nope", "--dir", client.toString(), "--yes");

        assertEquals(2, restore.exitCode());
        assertTrue(restore.getErrorOutput().contains("No backup in"), restore.getErrorOutput());
    }

    @Test
    void restoreAsksFirst(QuarkusMainLauncher launcher) {
        LaunchResult restore = launcher.launch("restore", "nope", "--dir", client.toString(), "--no-input");

        assertEquals(2, restore.exitCode());
        assertTrue(restore.getErrorOutput().contains("--yes"), restore.getErrorOutput());
    }

    @Test
    void aStepNeedsTheDeployment(QuarkusMainLauncher launcher) {
        LaunchResult prepare = launcher.launch("upgrade", "prepare");

        assertEquals(2, prepare.exitCode());
        assertTrue(prepare.getErrorOutput().contains("needs --dir"), prepare.getErrorOutput());
    }

    @Test
    void applyNeedsAPreparedVersion(QuarkusMainLauncher launcher) {
        LaunchResult apply = launcher.launch("upgrade", "apply", "--dir", client.toString());

        assertEquals(2, apply.exitCode());
        assertTrue(apply.getErrorOutput().contains("is not prepared"), apply.getErrorOutput());
    }

    @Test
    void rollbackNeedsAnUpgrade(QuarkusMainLauncher launcher) {
        LaunchResult rollback = launcher.launch("upgrade", "rollback", "--dir", client.toString());

        assertEquals(2, rollback.exitCode());
        assertTrue(rollback.getErrorOutput().contains("Nothing to roll back"), rollback.getErrorOutput());
    }

    @Test
    void deploymentOfAnOlderFlnetIsRefused(QuarkusMainLauncher launcher) throws IOException {
        Path env = client.resolve(".env");
        Files.writeString(env, Files.readString(env).replaceAll("(?m)^FLNET_DEPLOYMENT_KIND=.*$", ""));

        LaunchResult prepare = launcher.launch("upgrade", "prepare", "--dir", client.toString());

        assertEquals(3, prepare.exitCode());
        assertTrue(prepare.getErrorOutput().contains("No FLNET_DEPLOYMENT_KIND"), prepare.getErrorOutput());
    }

    @Test
    void anInterruptedBackupChangesNothing(QuarkusMainLauncher launcher) throws IOException {
        // died while the backup was written: back to prepared, then apply sees it prepared
        Files.createDirectories(client.resolve("updates"));
        Files.writeString(client.resolve("updates/state.json"), """
                {"step": "APPLY", "done": false, "flNetSoftwareVersion": "elsewhere", "backup": null}""");

        LaunchResult rollback = launcher.launch("upgrade", "rollback", "--dir", client.toString());

        assertEquals(2, rollback.exitCode());
        String state = Files.readString(client.resolve("updates/state.json"));
        assertTrue(state.contains("\"step\" : \"PREPARE\"") && state.contains("\"done\" : true"), state);
    }
}

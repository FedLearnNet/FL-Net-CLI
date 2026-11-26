package bio.cosy.flnet.cli.deploy.backup;

import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.helper.DirectoryHelper;
import bio.cosy.flnet.cli.helper.ProcessHelper;
import bio.cosy.flnet.cli.helper.ProcessResult;
import bio.cosy.flnet.cli.helper.ReleaseVersion;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class RestoreBO extends BaseBackupBO {

    /**
     * Databases, and files if the backup has them, back to the backup. Only the databases run afterwards.
     */
    public DeploymentBackup restore(BaseFLNetDeployableInstance instance, String name) {
        instance.requireInitialized();
        Path source = backupsDir(instance).resolve(requireValidName(name));
        DeploymentBackup backup = load(source);
        if (!backup.isComplete()) {
            throw new CliException("Backup '" + name + "' is incomplete and cannot be restored.");
        }
        if (backup.isBackUpAll()) {
            DirectoryHelper.copy(source.resolve(DeploymentBackup.FILES), instance.getDirectory(), List.of());
        } else {
            requireNotNewer(backup, instance);
        }
        restoreDatabases(instance, backup.getDatabases(), source.resolve(DeploymentBackup.DATABASES));
        return backup;
    }

    // the services migrate older databases forward, never newer ones back
    private static void requireNotNewer(DeploymentBackup backup, BaseFLNetDeployableInstance instance) {
        Optional<ReleaseVersion> of = ReleaseVersion.parse(backup.getVersion());
        Optional<ReleaseVersion> installed = ReleaseVersion.parse(instance.getImageTag());
        if (of.isPresent() && installed.isPresent() && of.get().compareTo(installed.get()) > 0) {
            throw CliException.usage("Backup '" + backup.getName() + "' holds only the databases of " + backup.getVersion()
                    + ", newer than the installed " + instance.getImageTag() + ". Upgrade to " + backup.getVersion() + " first.");
        }
    }

    private void restoreDatabases(BaseFLNetDeployableInstance instance, List<DatabaseDump> databases, Path dumps) {
        if (databases.isEmpty()) {
            return;
        }
        // nothing may write while the databases are replaced
        compose(instance, List.of("stop"));
        List<String> start = new ArrayList<>(List.of("up", "-d", "--wait"));
        databases.forEach(database -> start.add(database.getService()));
        compose(instance, start);
        for (DatabaseDump database : databases) {
            ConsoleHelper.info("Restoring " + database.getDatabase() + " of " + database.getService());
            String service = database.getService();
            String user = database.getUser();
            compose(instance, List.of("exec", "-T", service, "dropdb", "-U", user, "--force", "--if-exists", database.getDatabase()));
            compose(instance, List.of("exec", "-T", service, "createdb", "-U", user, database.getDatabase()));
            ProcessResult result = ProcessHelper.runFromFile(instance.getDirectory(), List.of("docker", "compose", "exec", "-T", service,
                    "psql", "-q", "-U", user, "-d", database.getDatabase(), "-v", "ON_ERROR_STOP=1"), dumps.resolve(database.getFile()));
            if (result.failed()) {
                throw new CliException("Restoring " + database.getDatabase() + " of " + service + " failed: " + result.reason(),
                        result.getCode());
            }
        }
    }

    private void compose(BaseFLNetDeployableInstance instance, List<String> args) {
        List<String> command = new ArrayList<>(List.of("docker", "compose"));
        command.addAll(args);
        int code = ProcessHelper.runInteractive(instance.getDirectory(), command);
        if (code != 0) {
            throw new CliException("'" + String.join(" ", command) + "' failed with exit code " + code + ".", code);
        }
    }
}

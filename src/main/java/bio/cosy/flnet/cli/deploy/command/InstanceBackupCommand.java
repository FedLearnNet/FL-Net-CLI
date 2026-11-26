package bio.cosy.flnet.cli.deploy.command;

import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.deploy.backup.BackupBO;
import bio.cosy.flnet.cli.deploy.backup.DatabaseDump;
import bio.cosy.flnet.cli.deploy.backup.DeploymentBackup;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.stream.Collectors;

@Command(name = "backup",
        description = {"Dump the databases of the deployment into backups/<backup>; with --all also copy the deployment "
                + "directory (configuration and secrets).",
                "Databases are the Postgres services of the deployment. The deployment has to run."})
public class InstanceBackupCommand extends ComposeCommands.ComposeCommand {

    @Parameters(paramLabel = "<backup>", arity = "0..1", description = "Name of the backup. Default: backup_<date>_<time> (UTC).")
    String backupName;

    @Option(names = "--all", description = "Also back up the deployment directory: configuration and secrets.")
    boolean all;

    @Inject
    BackupBO backupBO;

    @Override
    public Integer call() {
        BaseFLNetDeployableInstance selected = instance();
        DeploymentBackup backup = backupBO.create(selected, backupName, all);
        String databases = backup.getDatabases().stream().map(DatabaseDump::getDatabase).collect(Collectors.joining(", "));
        ConsoleHelper.success("Backup " + backup.getName() + " in " + backupBO.backupsDir(selected).resolve(backup.getName())
                + (databases.isEmpty() ? " (no database)." : " (" + databases + ")."));
        ConsoleHelper.info((all ? "It holds the secrets of the deployment. " : "") + "Restore it with:");
        ConsoleHelper.command("flnet " + parent.kind().bundle() + " restore " + backup.getName() + nameFlag());
        return 0;
    }
}

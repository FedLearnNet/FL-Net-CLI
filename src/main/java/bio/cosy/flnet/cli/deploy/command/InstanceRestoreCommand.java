package bio.cosy.flnet.cli.deploy.command;

import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.deploy.backup.DeploymentBackup;
import bio.cosy.flnet.cli.deploy.backup.RestoreBO;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "restore",
        description = {"Put a backup back: the databases and, for a backup made with --all, the deployment files.",
                "Everything written since the backup is lost. Afterwards only the databases run; start the rest with 'up'."})
public class InstanceRestoreCommand extends ComposeCommands.ComposeCommand {

    @Parameters(paramLabel = "<backup>", description = "Name of the backup in backups/.")
    String backupName;

    @Inject
    RestoreBO restoreBO;

    @Override
    public Integer call() {
        BaseFLNetDeployableInstance selected = instance();
        ConsoleHelper.warn("This replaces the configuration and ALL data of " + selected.getLabel() + " with backup "
                + backupName + ". Everything written since then is lost.");
        prompter.requireConfirmation("Restore the backup?");
        DeploymentBackup backup = restoreBO.restore(selected, backupName);
        ConsoleHelper.success(selected.getLabel() + " is back on backup " + backup.getName()
                + (backup.getVersion() == null ? "." : " (" + backup.getVersion() + ")."));
        ConsoleHelper.info("Start it with:");
        ConsoleHelper.command("flnet " + parent.kind().bundle() + " up" + nameFlag());
        return 0;
    }
}

package bio.cosy.flnet.cli.deploy.command;

import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.deploy.backup.BackupBO;
import bio.cosy.flnet.cli.deploy.backup.DeploymentBackup;
import bio.cosy.flnet.cli.deploy.bo.FLNetDeploymentsBO;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "backup",
        description = {"Back up the client or platform in <dir>: its databases, with --all also its configuration and secrets.",
                "The Admin Center runs the same in a container. By instance name: 'flnet <client|platform> backup'."},
        footer = {"", "Examples:", "  flnet backup --dir ~/fl-net/clients/default",
                "  flnet backup --all --dir ~/fl-net/clients/default"})
public class BackupCommand implements Callable<Integer> {

    @Parameters(paramLabel = "<backup>", arity = "0..1", description = "Name of the backup. Default: backup_<date>_<time> (UTC).")
    String backupName;

    @Option(names = {"-d", "--dir"}, paramLabel = "<dir>", required = true, description = "Directory of the deployment.")
    Path dir;

    @Option(names = "--all", description = "Also back up the deployment directory: configuration and secrets.")
    boolean all;

    @Inject
    FLNetDeploymentsBO deployments;

    @Inject
    BackupBO backupBO;

    @Override
    public Integer call() {
        BaseFLNetDeployableInstance instance = deployments.load(dir.toAbsolutePath().normalize());
        DeploymentBackup backup = backupBO.create(instance, backupName, all);
        ConsoleHelper.success("Backup " + backup.getName() + " in " + backupBO.backupsDir(instance).resolve(backup.getName()) + ".");
        return 0;
    }
}

package bio.cosy.flnet.cli.deploy.command;

import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.deploy.backup.DeploymentBackup;
import bio.cosy.flnet.cli.deploy.backup.RestoreBO;
import bio.cosy.flnet.cli.deploy.bo.ComposeBO;
import bio.cosy.flnet.cli.deploy.bo.FLNetDeploymentsBO;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.helper.InteractionOptions;
import bio.cosy.flnet.cli.helper.Prompter;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "restore",
        description = {"Put a backup back on the client or platform in <dir> and start it again: the databases and, for a "
                + "backup made with --all, the configuration and secrets. Everything written since the backup is lost.",
                "The Admin Center runs the same in a container. By instance name: 'flnet <client|platform> restore'."},
        footer = {"", "Example:", "  flnet restore backup_2026-11-26_14-24-31 --dir ~/fl-net/clients/default"})
public class RestoreCommand implements Callable<Integer> {

    @Parameters(paramLabel = "<backup>", description = "Name of the backup in backups/.")
    String backupName;

    @Option(names = {"-d", "--dir"}, paramLabel = "<dir>", required = true, description = "Directory of the deployment.")
    Path dir;

    @Option(names = "--wait-timeout", paramLabel = "<seconds>", defaultValue = "300",
            description = "Seconds the services may take to become healthy. Default: ${DEFAULT-VALUE}.")
    int waitTimeout;

    @Mixin
    InteractionOptions interaction;

    @Inject
    Prompter prompter;

    @Inject
    FLNetDeploymentsBO deployments;

    @Inject
    RestoreBO restoreBO;

    @Inject
    ComposeBO composeBO;

    @Override
    public Integer call() {
        Path directory = dir.toAbsolutePath().normalize();
        BaseFLNetDeployableInstance instance = deployments.load(directory);
        prompter.configure(interaction);
        ConsoleHelper.warn("This replaces ALL data of " + instance.getLabel() + " with backup " + backupName
                + ". Everything written since then is lost.");
        prompter.requireConfirmation("Restore the backup?");
        DeploymentBackup backup = restoreBO.restore(instance, backupName);
        // the files of the backup may hold another configuration
        int code = composeBO.up(deployments.load(directory), false, waitTimeout);
        if (code != 0) {
            throw new CliException("Starting the services failed with exit code " + code + ".", code);
        }
        ConsoleHelper.success(instance.getLabel() + " runs on backup " + backup.getName()
                + (backup.getVersion() == null ? "." : " (" + backup.getVersion() + ")."));
        return 0;
    }
}

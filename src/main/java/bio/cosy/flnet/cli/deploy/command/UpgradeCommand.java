package bio.cosy.flnet.cli.deploy.command;

import bio.cosy.flnet.cli.config.FLNetCliConfig;
import bio.cosy.flnet.cli.deploy.backup.BaseBackupBO;
import bio.cosy.flnet.cli.deploy.bo.UpgradeBO;
import bio.cosy.flnet.cli.deploy.upgrade.UpgradeState;
import bio.cosy.flnet.cli.deploy.upgrade.UpgradeStep;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.helper.ReleaseVersion;
import bio.cosy.flnet.cli.helper.VersionProvider;
import bio.cosy.flnet.cli.update.UpdateBO;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(name = "upgrade",
        description = {"Without a step: check for a newer flnet and show how to install it.",
                "With a step: upgrade a client or platform to the version of this flnet. prepare pulls the images while "
                        + "everything runs, apply backs up, installs and starts it and goes back to the backup if anything "
                        + "fails, rollback goes back to the backup of the last upgrade.",
                "The Admin Center runs the same in a container; the state is in <dir>/updates/state.json."},
        footer = {"", "Examples:", "  flnet upgrade", "  flnet upgrade prepare --dir ~/fl-net/clients/default",
                "  flnet upgrade apply --dir ~/fl-net/clients/default"})
public class UpgradeCommand implements Callable<Integer> {

    @Parameters(paramLabel = "<step>", arity = "0..1", description = "prepare, apply or rollback")
    UpgradeStep step;

    @Option(names = {"-d", "--dir"}, paramLabel = "<dir>", description = "Directory of the deployment, needed for a step.")
    Path dir;

    @Option(names = "--wait-timeout", paramLabel = "<seconds>", defaultValue = "300",
            description = "Seconds the services may take to become healthy. Default: ${DEFAULT-VALUE}.")
    int waitTimeout;

    @Inject
    UpgradeBO upgradeBO;

    @Inject
    UpdateBO updateBO;

    @Inject
    FLNetCliConfig config;

    @Override
    public Integer call() {
        if (step == null) {
            check();
            return 0;
        }
        if (dir == null) {
            throw CliException.usage("'flnet upgrade " + step.name().toLowerCase() + "' needs --dir <dir> of the deployment.");
        }
        Path directory = dir.toAbsolutePath().normalize();
        boolean success = upgradeBO.run(directory, step, waitTimeout);
        UpgradeState state = upgradeBO.load(directory.resolve(BaseBackupBO.UPDATES_DIRECTORY));
        if (state.getLastLogMessage() != null) {
            ConsoleHelper.info(state.getLastLogMessage());
        }
        return success ? 0 : 1;
    }

    private void check() {
        String installed = VersionProvider.version();
        Optional<ReleaseVersion> current = ReleaseVersion.parse(installed);
        ConsoleHelper.info("Installed: flnet " + installed);
        Optional<UpdateBO.Release> newer = UpdateBO.newer(updateBO.releases(), current, false);
        if (newer.isEmpty()) {
            ConsoleHelper.success("flnet is up to date. Upgrade a deployment to it with:");
        } else {
            String version = newer.get().version().toString();
            ConsoleHelper.info("Available: flnet " + version + (current.isEmpty() ? " (installed version unknown)" : ""));
            ConsoleHelper.info("Install it:");
            ConsoleHelper.command("curl -fsSL " + config.update().installUrl() + " | FLNET_VERSION=" + version + " sh");
            ConsoleHelper.info("Then upgrade a deployment to it:");
        }
        ConsoleHelper.command("flnet upgrade prepare --dir <dir>");
        ConsoleHelper.command("flnet upgrade apply --dir <dir>");
    }
}

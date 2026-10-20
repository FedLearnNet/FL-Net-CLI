package bio.cosy.flnet.cli.update;

import bio.cosy.flnet.cli.config.FLNetCliConfig;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.helper.ReleaseVersion;
import bio.cosy.flnet.cli.helper.VersionProvider;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Optional;
import java.util.concurrent.Callable;

@Command(name = "update", description = {
        "Check whether a newer flnet version is available and show how to install it.",
        "Release candidates are only offered with --pre or when this flnet is one itself."
})
public class UpdateCommand implements Callable<Integer> {

    @Option(names = "--pre", description = "Also offer release candidates (X.Y.Z.rcN).")
    boolean includeCandidates;

    @Inject
    UpdateBO updateBO;

    @Inject
    FLNetCliConfig config;

    @Override
    public Integer call() {
        String installed = VersionProvider.version();
        Optional<ReleaseVersion> current = ReleaseVersion.parse(installed);
        Optional<UpdateBO.Release> newer = UpdateBO.newer(updateBO.releases(), current, includeCandidates);

        ConsoleHelper.info("Installed: flnet " + installed);
        if (newer.isEmpty()) {
            ConsoleHelper.success("flnet is up to date.");
            return 0;
        }
        String version = newer.get().version().toString();
        ConsoleHelper.info("Available: flnet " + version + (current.isEmpty() ? " (installed version unknown)" : ""));
        ConsoleHelper.blank();
        ConsoleHelper.info("To update, run:");
        ConsoleHelper.command("curl -fsSL " + config.update().installUrl() + " | FLNET_VERSION=" + version + " sh");
        ConsoleHelper.info("Release notes: " + newer.get().url());
        return 0;
    }
}

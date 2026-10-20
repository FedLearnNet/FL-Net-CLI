package bio.cosy.flnet.cli.update;

import bio.cosy.flnet.cli.config.FLNetCliConfig;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.helper.ReleaseVersion;
import bio.cosy.flnet.cli.helper.VersionProvider;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;

import java.util.Optional;
import java.util.concurrent.Callable;

// TODO: tell them how to actually update their client
@Command(name = "update", description = {
        "Check whether a newer flnet version is available."
})
public class UpdateCommand implements Callable<Integer> {

    // @Option(names = "--pre", description = "Also offer release candidates (X.Y.Z.rcN).")
    // boolean includeCandidates;
    // Currently RC is not used

    @Inject
    UpdateBO updateBO;

    @Inject
    FLNetCliConfig config;

    @Override
    public Integer call() {
        String installed = VersionProvider.version();
        Optional<ReleaseVersion> current = ReleaseVersion.parse(installed);
        Optional<UpdateBO.Release> newer = UpdateBO.newer(updateBO.releases(), current, false);

        ConsoleHelper.info("Installed: flnet " + installed);
        if (newer.isEmpty()) {
            ConsoleHelper.success("flnet is up to date.");
            return 0;
        }
        String version = newer.get().version().toString();
        ConsoleHelper.info("Available: flnet " + version + (current.isEmpty() ? " (installed version unknown)" : ""));
        return 0;
    }
}

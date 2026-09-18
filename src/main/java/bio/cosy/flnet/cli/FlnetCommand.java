package bio.cosy.flnet.cli;

import bio.cosy.flnet.cli.client.ClientCommand;
import bio.cosy.flnet.cli.platform.PlatformCommand;
import bio.cosy.flnet.cli.tool.ToolCommand;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.AutoComplete;
import picocli.CommandLine.Command;
import picocli.CommandLine.ScopeType;

@TopCommand
@Command(name = "flnet",
        mixinStandardHelpOptions = true,
        // --help/--version and the help layout apply to every subcommand
        scope = ScopeType.INHERIT,
        usageHelpAutoWidth = true,
        versionProvider = VersionProvider.class,
        description = {
                "Command line tool for FL-Net, the open federated learning network.",
                "Set up a FL-Net Platform or Client with docker compose, and create new FL-Net tools."
        },
        subcommands = {
                ClientCommand.class,
                PlatformCommand.class,
                ToolCommand.class,
                ListCommand.class,
                DoctorCommand.class,
                AutoComplete.GenerateCompletion.class,
        },
        footer = {
                "",
                "Get started:",
                "  flnet doctor               check prerequisites",
                "  flnet client init          set up a client that joins a network",
                "  flnet platform init        set up your own network platform",
                "  flnet tool create <name>   start a new tool project",
                "  flnet list                 show all clients and platforms on this machine",
                "",
                "Settings: ~/.config/flnet/application.properties (see flnet.* in the built-in application.properties)",
                "",
                "Documentation: ${bundle:flnet.documentation-url}"
        })
public class FlnetCommand {
}

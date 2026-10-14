package bio.cosy.flnet.cli.sandbox.command;

import picocli.CommandLine.Command;

@Command(name = "sandbox",
        description = {
                "Run a complete FL-Net network on this machine: one platform and several clients with test data.",
                "For trying FL-Net and for testing changes end to end. Not for real data."
        },
        subcommands = {
                SandboxInitCommand.class,
                SandboxCommands.Up.class,
                SandboxCommands.Status.class,
                SandboxCommands.Seed.class,
                SandboxImagesCommand.class,
                SandboxCommands.Logs.class,
                SandboxCommands.Restart.class,
                SandboxCommands.Stop.class,
                SandboxCommands.Down.class,
                SandboxCommands.Clean.class,
        },
        footer = {
                "",
                "Get started:",
                "  flnet sandbox init",
                "  flnet sandbox up",
                "  flnet sandbox seed",
                "",
                "One machine has one sandbox. It lives in its own folder ($FLNET_HOME/sandbox) and is not part of",
                "'flnet client' and 'flnet platform'. Its members are called platform, c1, c2, ..."
        })
public class SandboxCommand {
}

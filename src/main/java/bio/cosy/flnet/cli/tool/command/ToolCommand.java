package bio.cosy.flnet.cli.tool.command;

import picocli.CommandLine.Command;

@Command(name = "tool",
        description = "Develop FL-Net tools (analyses, preprocessing, federated learning apps, ...).",
        subcommands = ToolCreateCommand.class)
public class ToolCommand {
}

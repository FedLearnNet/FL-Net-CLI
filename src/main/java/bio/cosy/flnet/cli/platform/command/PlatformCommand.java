package bio.cosy.flnet.cli.platform.command;

import bio.cosy.flnet.cli.base.deployment.DeploymentKind;
import bio.cosy.flnet.cli.deploy.command.ComposeCommands;
import bio.cosy.flnet.cli.deploy.command.DeploymentGroup;
import bio.cosy.flnet.cli.deploy.command.InstanceCommands;
import picocli.CommandLine.Command;

@Command(name = "platform",
        description = "Set up and operate a self-deployed FL-Net Platform (the global part of a network).",
        subcommands = {
                PlatformInitCommand.class,
                InstanceCommands.ListInstances.class,
                InstanceCommands.Info.class,
                ComposeCommands.Up.class,
                ComposeCommands.Down.class,
                ComposeCommands.Pull.class,
                ComposeCommands.Stop.class,
                ComposeCommands.Restart.class,
                ComposeCommands.Clean.class,
                ComposeCommands.Status.class,
                ComposeCommands.Logs.class,
                ComposeCommands.Compose.class,
        })
public class PlatformCommand implements DeploymentGroup {

    @Override
    public DeploymentKind kind() {
        return DeploymentKind.PLATFORM;
    }
}

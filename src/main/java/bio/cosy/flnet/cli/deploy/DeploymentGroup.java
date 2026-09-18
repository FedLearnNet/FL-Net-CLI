package bio.cosy.flnet.cli.deploy;

import bio.cosy.flnet.cli.base.DeploymentKind;

/**
 * Implemented by the {@code platform} and {@code client} parent commands, so the shared
 * {@code up/down/status/logs} subcommands know which deployment they operate on.
 */
public interface DeploymentGroup {
    DeploymentKind kind();
}

package bio.cosy.flnet.cli.deploy.command;

import bio.cosy.flnet.cli.base.deployment.DeploymentKind;

public interface DeploymentGroup {
    DeploymentKind kind();
}

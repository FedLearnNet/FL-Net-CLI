package bio.cosy.flnet.cli.base.deployment;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum DeploymentKind {
    PLATFORM("platform", "platforms", "FL-Net Platform"),
    CLIENT("client", "clients", "FL-Net Client");

    private final String bundle;
    private final String folder;
    private final String displayName;
}

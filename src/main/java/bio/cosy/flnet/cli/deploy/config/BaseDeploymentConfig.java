package bio.cosy.flnet.cli.deploy.config;

import lombok.Getter;
import lombok.Setter;
import picocli.CommandLine.Option;


@Getter
@Setter
public abstract class BaseDeploymentConfig {

    @Option(names = "--image-tag", paramLabel = "<tag>", description = "Image tag. Default: ${bundle:flnet.images.tag}.")
    private String imageTag;

    @Option(names = "--frontend-image", paramLabel = "<image>",
            description = "Override the frontend image (derived from the network or domain by default).")
    private String frontendImage;
}

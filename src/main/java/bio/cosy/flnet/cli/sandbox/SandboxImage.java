package bio.cosy.flnet.cli.sandbox;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The FL-Net images of a sandbox that can be replaced with a self-built one, and the compose service that
 * runs them in the platform and client bundle.
 */
@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum SandboxImage {
    ORCH_API("orch-api", "orch-api", "orch-api"),
    LOCAL_LEARNING_API("local-learning-api", null, "local-learning-api"),
    GLOBAL_LEARNING_API("global-learning-api", "global-learning-api", null),
    DATAMODELER_API("datamodeler-api", "datamodeler-api", null),
    CONTROLLER("controller", "controller", "controller"),
    RELAY_SERVER("relay-server", "relay-server", null),
    LOCAL_FRONTEND("local-frontend", null, "instance-manager-frontend"),
    GLOBAL_FRONTEND("global-frontend", "frontend", null),
    // not a compose service: the platform starts it to build tools
    PIPELINE_BUILDER("pipeline-builder", null, null);

    private final String key;
    private final String platformService;
    private final String clientService;

    public static Optional<SandboxImage> byKey(String key) {
        return Arrays.stream(values()).filter(image -> image.key.equals(key)).findFirst();
    }

    public static String keys() {
        return Arrays.stream(values()).map(SandboxImage::key).collect(Collectors.joining(", "));
    }
}

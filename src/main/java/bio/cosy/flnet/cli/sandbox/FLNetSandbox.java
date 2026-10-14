package bio.cosy.flnet.cli.sandbox;

import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.base.deployment.FLNetClientDeployment;
import bio.cosy.flnet.cli.base.deployment.FLNetPlatformDeployment;
import bio.cosy.flnet.cli.helper.CliException;
import lombok.Getter;
import lombok.Setter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One platform and several clients on this machine that form a local network. All of it lives in one directory.
 */
@Getter
@Setter
public class FLNetSandbox {

    public static final String IMAGES_FILE = "images.env";
    public static final String SEED_FOLDER = "seed";
    public static final String README = "README.md";

    private final Path directory;
    private FLNetPlatformDeployment platform;
    private final List<FLNetClientDeployment> clients = new ArrayList<>();
    private final Map<SandboxImage, String> images = new EnumMap<>(SandboxImage.class);

    public FLNetSandbox(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public List<BaseFLNetDeployableInstance> getMembers() {
        List<BaseFLNetDeployableInstance> members = new ArrayList<>();
        if (platform != null) {
            members.add(platform);
        }
        members.addAll(clients);
        return members;
    }

    public BaseFLNetDeployableInstance member(String name) {
        return getMembers().stream().filter(member -> member.getName().equals(name)).findFirst()
                .orElseThrow(() -> CliException.usage("The sandbox has no member '" + name + "'. Members: "
                        + getMembers().stream().map(BaseFLNetDeployableInstance::getName).collect(Collectors.joining(", ")) + "."));
    }

    public Path getImagesFile() {
        return directory.resolve(IMAGES_FILE);
    }

    public Path getSeedDirectory() {
        return directory.resolve(SEED_FOLDER);
    }

    public Path getReadme() {
        return directory.resolve(README);
    }
}

package bio.cosy.flnet.cli.deploy.bo;

import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.base.deployment.DeploymentKind;
import bio.cosy.flnet.cli.base.deployment.FLNetClientDeployment;
import bio.cosy.flnet.cli.base.deployment.FLNetPlatformDeployment;
import bio.cosy.flnet.cli.client.bo.FLNetClientDeploymentBO;
import bio.cosy.flnet.cli.platform.bo.FLNetPlatformDeploymentBO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@ApplicationScoped
public class FLNetDeploymentsBO {

    public static final String SANDBOX_FOLDER = "sandbox";
    public static final String SANDBOX_PLATFORM = "platform";
    public static final String SANDBOX_CLIENTS = "clients";

    @Inject
    FLNetClientDeploymentBO clientBO;

    @Inject
    FLNetPlatformDeploymentBO platformBO;

    public BaseFLNetDeploymentBO<? extends BaseFLNetDeployableInstance> bo(DeploymentKind kind) {
        return kind == DeploymentKind.CLIENT ? clientBO : platformBO;
    }

    public List<BaseFLNetDeployableInstance> listAll() {
        return Stream.concat(platformBO.list().stream(), clientBO.list().stream())
                .map(BaseFLNetDeployableInstance.class::cast)
                .toList();
    }

    public Path sandboxDir() {
        return home().resolve(SANDBOX_FOLDER);
    }

    public Optional<FLNetPlatformDeployment> sandboxPlatform() {
        Path dir = sandboxDir().resolve(SANDBOX_PLATFORM);
        return Files.isRegularFile(dir.resolve(".env")) ? Optional.of(platformBO.load(dir, SANDBOX_PLATFORM)) : Optional.empty();
    }

    public List<FLNetClientDeployment> sandboxClients() {
        Path root = sandboxDir().resolve(SANDBOX_CLIENTS);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(root)) {
            return entries.filter(entry -> Files.isRegularFile(entry.resolve(".env")))
                    .map(entry -> clientBO.load(entry, entry.getFileName().toString()))
                    .sorted(Comparator.comparing(FLNetClientDeployment::getName))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + root, e);
        }
    }

    public List<BaseFLNetDeployableInstance> sandboxMembers() {
        return Stream.concat(sandboxPlatform().stream(), sandboxClients().stream())
                .map(BaseFLNetDeployableInstance.class::cast)
                .toList();
    }

    public List<BaseFLNetDeployableInstance> listAllWithSandbox() {
        return Stream.concat(listAll().stream(), sandboxMembers().stream()).toList();
    }

    public String nameFlag(BaseFLNetDeployableInstance instance) {
        return bo(instance.getKind()).nameFlag(instance);
    }

    public Path home() {
        return clientBO.home();
    }
}

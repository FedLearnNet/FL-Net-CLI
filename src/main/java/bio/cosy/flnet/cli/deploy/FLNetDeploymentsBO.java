package bio.cosy.flnet.cli.deploy;

import bio.cosy.flnet.cli.client.FLNetClientDeploymentBO;
import bio.cosy.flnet.cli.base.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.base.DeploymentKind;
import bio.cosy.flnet.cli.platform.FLNetPlatformDeploymentBO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Access to the deployments of both kinds, e.g. for {@code flnet list} or port planning. */
@ApplicationScoped
public class FLNetDeploymentsBO {

    @Inject
    FLNetClientDeploymentBO clientBO;

    @Inject
    FLNetPlatformDeploymentBO platformBO;

    /** The BO of a deployment kind (for commands shared by platform and client). */
    public BaseFLNetDeploymentBO<? extends BaseFLNetDeployableInstance> bo(DeploymentKind kind) {
        return kind == DeploymentKind.CLIENT ? clientBO : platformBO;
    }

    /** Platforms first, then clients. */
    public List<BaseFLNetDeployableInstance> listAll() {
        return Stream.concat(platformBO.list().stream(), clientBO.list().stream())
                .map(BaseFLNetDeployableInstance.class::cast)
                .toList();
    }

    /** {@code " --name x"} when the deployment is not the only one of its kind, else empty. */
    public String nameFlag(BaseFLNetDeployableInstance instance) {
        return bo(instance.getKind()).nameFlag(instance);
    }

    public Path home() {
        return clientBO.home();
    }
}

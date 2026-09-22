package bio.cosy.flnet.cli.deploy.bo;

import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.base.deployment.DeploymentKind;
import bio.cosy.flnet.cli.client.bo.FLNetClientDeploymentBO;
import bio.cosy.flnet.cli.platform.bo.FLNetPlatformDeploymentBO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

@ApplicationScoped
public class FLNetDeploymentsBO {

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

    public String nameFlag(BaseFLNetDeployableInstance instance) {
        return bo(instance.getKind()).nameFlag(instance);
    }

    public Path home() {
        return clientBO.home();
    }
}

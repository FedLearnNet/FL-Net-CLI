package bio.cosy.flnet.cli.deploy.bo;

import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.base.deployment.DeploymentKind;
import bio.cosy.flnet.cli.base.env.CommonEnv;
import bio.cosy.flnet.cli.client.bo.FLNetClientDeploymentBO;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.EnvFileHelper;
import bio.cosy.flnet.cli.platform.bo.FLNetPlatformDeploymentBO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.util.Arrays;
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

    /**
     * The deployment in a directory, of the kind its .env names; for commands that get only --dir.
     */
    public BaseFLNetDeployableInstance load(Path directory) {
        BaseFLNetDeployableInstance instance = bo(kindOf(directory)).load(directory, directory.getFileName().toString());
        instance.requireInitialized();
        return instance;
    }

    public static DeploymentKind kindOf(Path directory) {
        String kind = EnvFileHelper.read(directory.resolve(".env")).get(CommonEnv.FLNET_DEPLOYMENT_KIND.key());
        return Arrays.stream(DeploymentKind.values()).filter(value -> value.bundle().equals(kind)).findFirst()
                .orElseThrow(() -> new CliException("No " + CommonEnv.FLNET_DEPLOYMENT_KIND.key() + " in " + directory.resolve(".env")
                        + ". Run 'flnet <client|platform> init --dir " + directory + "' once with this flnet.", CliException.ENVIRONMENT));
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

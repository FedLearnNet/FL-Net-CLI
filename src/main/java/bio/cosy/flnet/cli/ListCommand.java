package bio.cosy.flnet.cli;

import bio.cosy.flnet.cli.deploy.ComposeBO;
import bio.cosy.flnet.cli.deploy.FLNetDeploymentsBO;
import bio.cosy.flnet.cli.deploy.InstanceReport;
import bio.cosy.flnet.cli.base.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.support.Ui;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;

import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "list", aliases = "ls", description = "List all FL-Net platforms and clients on this machine.")
public class ListCommand implements Callable<Integer> {

    @Inject
    FLNetDeploymentsBO deployments;

    @Inject
    ComposeBO composeBO;

    @Override
    public Integer call() {
        List<BaseFLNetDeployableInstance> all = deployments.listAll();
        if (all.isEmpty()) {
            Ui.info("Nothing is set up on this machine yet (" + deployments.home() + ").");
            Ui.info("Get started with 'flnet client init' or 'flnet platform init'.");
            return 0;
        }
        InstanceReport.table(composeBO, all);
        return 0;
    }
}

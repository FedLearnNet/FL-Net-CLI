package bio.cosy.flnet.cli.deploy.command;

import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.deploy.InstanceReportHelper;
import bio.cosy.flnet.cli.deploy.bo.ComposeBO;
import bio.cosy.flnet.cli.deploy.bo.FLNetDeploymentsBO;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
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
        // the sandbox is managed as a whole and has its own commands
        int sandboxMembers = deployments.sandboxMembers().size();
        if (all.isEmpty()) {
            ConsoleHelper.info("Nothing is set up on this machine yet (" + deployments.home() + ").");
            ConsoleHelper.info("Get started with 'flnet client init' or 'flnet platform init'.");
        } else {
            InstanceReportHelper.table(composeBO, all);
        }
        if (sandboxMembers > 0) {
            ConsoleHelper.blank();
            ConsoleHelper.info("A sandbox (platform and " + (sandboxMembers - 1) + " clients) is set up in " + deployments.sandboxDir() + ":");
            ConsoleHelper.command("flnet sandbox status");
        }
        return 0;
    }
}

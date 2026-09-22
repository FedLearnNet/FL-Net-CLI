package bio.cosy.flnet.cli.deploy.command;

import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.deploy.InstanceReportHelper;
import bio.cosy.flnet.cli.deploy.bo.BaseFLNetDeploymentBO;
import bio.cosy.flnet.cli.deploy.bo.ComposeBO;
import bio.cosy.flnet.cli.deploy.bo.FLNetDeploymentsBO;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.util.List;
import java.util.concurrent.Callable;

public final class InstanceCommands {

    private InstanceCommands() {
    }

    @Command(name = "list", aliases = "ls", description = "List all instances on this machine.")
    public static class ListInstances implements Callable<Integer> {

        @ParentCommand
        DeploymentGroup parent;

        @Inject
        FLNetDeploymentsBO deployments;

        @Inject
        ComposeBO composeBO;

        @Override
        public Integer call() {
            BaseFLNetDeploymentBO<?> bo = deployments.bo(parent.kind());
            List<? extends BaseFLNetDeployableInstance> all = bo.list();
            if (all.isEmpty()) {
                noInstances(bo);
            } else {
                InstanceReportHelper.table(composeBO, all);
            }
            return 0;
        }
    }

    @Command(name = "info",
            description = "Show an instance in detail. With several instances and no --name, lists them instead.")
    public static class Info implements Callable<Integer> {

        @Option(names = "--env", description = "Also list every .env variable with its value and what it means.")
        boolean env;

        @ParentCommand
        DeploymentGroup parent;

        @Mixin
        InstanceOptions instanceOptions;

        @Inject
        FLNetDeploymentsBO deployments;

        @Inject
        ComposeBO composeBO;

        @Override
        public Integer call() {
            BaseFLNetDeploymentBO<?> bo = deployments.bo(parent.kind());
            if (instanceOptions.name == null && instanceOptions.dir == null) {
                List<? extends BaseFLNetDeployableInstance> all = bo.list();
                if (all.isEmpty()) {
                    noInstances(bo);
                    return 0;
                }
                if (all.size() > 1) {
                    InstanceReportHelper.table(composeBO, all);
                    ConsoleHelper.blank();
                    ConsoleHelper.info("Details of one: flnet " + bo.kind().bundle() + " info --name <name>");
                    return 0;
                }
                show(all.getFirst(), bo);
                return 0;
            }
            show(bo.select(instanceOptions, null), bo);
            return 0;
        }

        private void show(BaseFLNetDeployableInstance instance, BaseFLNetDeploymentBO<?> bo) {
            InstanceReportHelper.details(composeBO, instance, bo.nameFlag(instance));
            if (env) {
                ConsoleHelper.blank();
                InstanceReportHelper.env(instance);
            }
        }
    }

    static void noInstances(BaseFLNetDeploymentBO<?> bo) {
        ConsoleHelper.info("No " + bo.kind().displayName() + " is set up on this machine (" + bo.instancesDir() + ").");
        ConsoleHelper.info("Set one up with:");
        ConsoleHelper.command("flnet " + bo.kind().bundle() + " init");
    }
}

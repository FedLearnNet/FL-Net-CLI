package bio.cosy.flnet.cli.deploy;

import bio.cosy.flnet.cli.base.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.support.Ui;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.util.List;
import java.util.concurrent.Callable;

/** {@code list} and {@code info}, shared by {@code flnet platform} and {@code flnet client}. */
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
                InstanceReport.table(composeBO, all);
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
                    InstanceReport.table(composeBO, all);
                    Ui.blank();
                    Ui.info("Details of one: flnet " + bo.kind().bundle() + " info --name <name>");
                    return 0;
                }
                show(all.getFirst(), bo);
                return 0;
            }
            show(bo.select(instanceOptions, null), bo);
            return 0;
        }

        private void show(BaseFLNetDeployableInstance instance, BaseFLNetDeploymentBO<?> bo) {
            InstanceReport.details(composeBO, instance, bo.nameFlag(instance));
            if (env) {
                Ui.blank();
                InstanceReport.env(instance);
            }
        }
    }

    static void noInstances(BaseFLNetDeploymentBO<?> bo) {
        Ui.info("No " + bo.kind().displayName() + " is set up on this machine (" + bo.instancesDir() + ").");
        Ui.info("Set one up with:");
        Ui.command("flnet " + bo.kind().bundle() + " init");
    }
}

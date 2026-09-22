package bio.cosy.flnet.cli.migration.command;

import bio.cosy.flnet.cli.base.deployment.DeploymentKind;
import bio.cosy.flnet.cli.deploy.bo.FLNetDeploymentsBO;
import bio.cosy.flnet.cli.deploy.command.InstanceOptions;
import bio.cosy.flnet.cli.migration.bo.FLNetMigrationBO;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.helper.InteractionOptions;
import bio.cosy.flnet.cli.helper.Prompter;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command(name = "migrate", description = "Explain and apply the JSON migration tasks bundled with this CLI.")
public class MigrateCommand implements Callable<Integer> {
    @Option(names = "--kind", required = true, description = "Deployment kind: ${COMPLETION-CANDIDATES}.")
    DeploymentKind kind;
    @Option(names = "--dry-run", description = "Explain the changes without writing files.")
    boolean dryRun;
    @Option(names = "--from", description = "Known current revision, required for older deployments without a recorded version.")
    Integer from;
    @Mixin
    InstanceOptions instance = new InstanceOptions();
    @Mixin
    InteractionOptions interaction = new InteractionOptions();
    @Inject
    FLNetDeploymentsBO deployments;
    @Inject
    FLNetMigrationBO migrations;
    @Inject
    Prompter prompter;

    @Override
    public Integer call() throws Exception {
        prompter.configure(interaction);
        var directory = deployments.bo(kind).select(instance, prompter).getDirectory();
        var plan = migrations.plan(kind, directory, from);
        ConsoleHelper.info(kind.displayName() + " at " + plan.getDirectory());
        ConsoleHelper.info("Deployment revision: " + plan.getCurrentRevision() + " -> " + plan.getTargetRevision());
        if (plan.getMigrations().isEmpty()) {
            ConsoleHelper.success("Already at the newest deployment revision bundled with this CLI.");
            return 0;
        }
        for (var migration : plan.getMigrations()) {
            ConsoleHelper.info("Revision " + migration.getRevision() + ": " + migration.getDescription());
            migration.getTasks().forEach(task -> ConsoleHelper.info("  " + task.getType() + " " + task.getPath() + ": " + task.getDescription()));
        }
        ConsoleHelper.info("These files will change. Containers are not restarted. There is no automatic backup or recovery.");
        if (dryRun) return 0;
        prompter.requireConfirmation("Apply these migration tasks?");
        migrations.migrateToNewest(plan);
        ConsoleHelper.success("Migrated to revision " + plan.getTargetRevision() + ".");
        ConsoleHelper.info("Start the updated deployment with flnet " + kind.bundle() + " up --dir " + plan.getDirectory());
        return 0;
    }
}

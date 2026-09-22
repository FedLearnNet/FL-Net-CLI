package bio.cosy.flnet.cli.deploy.command;

import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.deploy.bo.ComposeBO;
import bio.cosy.flnet.cli.deploy.bo.FLNetDeploymentsBO;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.helper.InteractionOptions;
import bio.cosy.flnet.cli.helper.Prompter;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

public final class ComposeCommands {

    private ComposeCommands() {
    }

    abstract static class ComposeCommand implements Callable<Integer> {

        @ParentCommand
        DeploymentGroup parent;

        @Mixin
        InstanceOptions instanceOptions;

        @Mixin
        InteractionOptions interaction;

        @Inject
        Prompter prompter;

        @Inject
        FLNetDeploymentsBO deployments;

        @Inject
        ComposeBO composeBO;

        private BaseFLNetDeployableInstance instance;

        BaseFLNetDeployableInstance instance() {
            if (instance == null) {
                prompter.configure(interaction);
                instance = deployments.bo(parent.kind()).select(instanceOptions, prompter);
            }
            return instance;
        }

        String nameFlag() {
            return deployments.nameFlag(instance());
        }
    }

    @Command(name = "up", description = "Start (or apply configuration changes to) the deployment in the background.")
    public static class Up extends ComposeCommand {

        @Option(names = "--pull", description = "Pull the latest images before starting.")
        boolean pull;

        @Override
        public Integer call() {
            int code = composeBO.up(instance(), pull);
            if (code == 0) {
                ConsoleHelper.success(instance().getKind().displayName() + " '" + instance().getName() + "' is starting.");
                ConsoleHelper.info("Follow the startup with:");
                ConsoleHelper.command("flnet " + parent.kind().bundle() + " logs -f" + nameFlag());
            }
            return code;
        }
    }

    @Command(name = "down", description = "Stop the deployment. Data is kept unless --volumes is given.")
    public static class Down extends ComposeCommand {

        @Option(names = {"-v", "--volumes"}, description = "Also delete all volumes, i.e. ALL data of this deployment.")
        boolean volumes;

        @Override
        public Integer call() {
            BaseFLNetDeployableInstance selected = instance();
            if (volumes) {
                ConsoleHelper.warn("--volumes permanently deletes ALL data of " + selected.getLabel()
                        + " (users, cohorts, learning results, databases).");
                prompter.requireConfirmation("Delete all data?");
            }
            return composeBO.down(selected, volumes);
        }
    }

    @Command(name = "pull",
            description = "Download the latest images. A running deployment is recreated with them, unless --no-restart is given.")
    public static class Pull extends ComposeCommand {

        @Option(names = "--no-restart", description = "Only download; the new images are used on the next 'up'.")
        boolean noRestart;

        @Override
        public Integer call() {
            BaseFLNetDeployableInstance selected = instance();
            int code = composeBO.pull(selected);
            if (code != 0) {
                return code;
            }
            if (!noRestart && composeBO.isRunning(selected)) {
                return composeBO.up(selected, false);
            }
            ConsoleHelper.success("Images of " + selected.getLabel() + " are up to date. Start or recreate the containers with:");
            ConsoleHelper.command("flnet " + parent.kind().bundle() + " up" + nameFlag());
            return 0;
        }
    }

    @Command(name = "stop", description = "Stop the containers without removing them. Continue with 'up' or 'restart'.")
    public static class Stop extends ComposeCommand {

        @Parameters(paramLabel = "<service>", arity = "0..*", description = "Only these services (see 'status').")
        List<String> services = new ArrayList<>();

        @Override
        public Integer call() {
            return composeBO.stop(instance(), services);
        }
    }

    @Command(name = "restart",
            description = {"Restart the containers as they are.", "Changes of .env or new images need 'up' instead, which recreates changed containers."})
    public static class Restart extends ComposeCommand {

        @Parameters(paramLabel = "<service>", arity = "0..*", description = "Only these services (see 'status').")
        List<String> services = new ArrayList<>();

        @Override
        public Integer call() {
            return composeBO.restart(instance(), services);
        }
    }

    @Command(name = "clean",
            description = {"Remove containers, networks and volumes (ALL data) of this deployment for a fresh start.",
                    "With --purge also delete its directory (configuration and secrets), so it is gone from 'list'."})
    public static class Clean extends ComposeCommand {

        @Option(names = "--images", description = "Also remove the images (other instances of the same kind pull them again on 'up').")
        boolean images;

        @Option(names = "--purge", description = "Also delete the deployment directory, including .env and all secrets.")
        boolean purge;

        @Override
        public Integer call() {
            BaseFLNetDeployableInstance selected = instance();
            ConsoleHelper.warn("This permanently deletes ALL data of " + selected.getLabel()
                    + " (users, cohorts, learning results, databases) and removes its containers" + (images ? " and images." : "."));
            if (purge) {
                ConsoleHelper.warn("--purge also deletes " + selected.getDirectory() + " with its configuration and secrets.");
            }
            prompter.requireConfirmation(purge ? "Delete all data and the configuration?" : "Delete all data?");
            int code = composeBO.clean(selected, images);
            if (code != 0) {
                return code;
            }
            if (purge) {
                deployments.bo(parent.kind()).delete(selected);
                ConsoleHelper.success(selected.getKind().displayName() + " '" + selected.getName() + "' is removed completely.");
            } else {
                ConsoleHelper.success("Removed containers and data. The configuration is kept; start fresh with:");
                ConsoleHelper.command("flnet " + parent.kind().bundle() + " up" + nameFlag());
            }
            return 0;
        }
    }

    @Command(name = "status", description = "Show the state of all services.")
    public static class Status extends ComposeCommand {
        @Override
        public Integer call() {
            return composeBO.status(instance());
        }
    }

    @Command(name = "logs", description = "Show service logs.")
    public static class Logs extends ComposeCommand {

        @Option(names = {"-f", "--follow"}, description = "Follow the log output.")
        boolean follow;

        @Option(names = "--tail", paramLabel = "<n>", defaultValue = "200", description = "Lines per service to show. Default: ${DEFAULT-VALUE}.")
        String tail;

        @Parameters(paramLabel = "<service>", arity = "0..*", description = "Only these services (see 'status').")
        List<String> services = new ArrayList<>();

        @Override
        public Integer call() {
            return composeBO.logs(instance(), tail, follow, services);
        }
    }

    @Command(name = "compose",
            description = {
                    "Generate one standalone docker compose file for this deployment (e.g. for Portainer).",
                    "Resolves profiles, paths and all values from .env and env/*.env (including secrets)."
            },
            footer = {
                    "",
                    "Examples:",
                    "  flnet client compose",
                    "  flnet platform compose -o stack.yml",
                    "  flnet client compose -o - --keep-variables > shareable.yml"
            })
    public static class Compose extends ComposeCommand {

        @Option(names = {"-o", "--output"}, paramLabel = "<file>",
                description = "Target file, or '-' for stdout. Default: <dir>/${bundle:flnet.compose.generated-file}.")
        String output;

        @Option(names = "--keep-variables",
                description = "Keep $${VAR} references and env_file entries instead of inlining values; the file then contains no secrets.")
        boolean keepVariables;

        @Override
        public Integer call() {
            printExport(composeBO, instance(), output, keepVariables);
            return 0;
        }
    }

    public static void printExport(ComposeBO composeBO, BaseFLNetDeployableInstance instance, String output, boolean keepVariables) {
        composeBO.export(instance, output, keepVariables).ifPresent(file -> {
            ConsoleHelper.success("Generated " + file);
            if (keepVariables) {
                ConsoleHelper.info("Values stay in " + instance.getEnvFile() + " and " + instance.getSecretsDirectory()
                        + ", which must exist where the file is used.");
            } else {
                ConsoleHelper.warn("The file contains all secrets of this deployment in plain text (readable by your user only). "
                        + "Do not commit or share it; use --keep-variables for a shareable version.");
            }
            ConsoleHelper.info("Bind mounts point to " + instance.getDirectory() + ", so use the file on this machine:");
            ConsoleHelper.command("docker compose -f " + file + " up -d");
        });
    }
}

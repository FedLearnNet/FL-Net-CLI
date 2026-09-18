package bio.cosy.flnet.cli.deploy;

import bio.cosy.flnet.cli.base.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.support.InteractionOptions;
import bio.cosy.flnet.cli.support.Prompter;
import bio.cosy.flnet.cli.support.Ui;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** docker compose commands shared by {@code flnet platform} and {@code flnet client}. */
public final class ComposeCommands {

    private ComposeCommands() {
    }

    /** Base: selects the deployment; the work is done by {@link ComposeBO}. */
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

        /** The selected deployment (resolved once: selecting may ask the user). */
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
                Ui.success(instance().getKind().displayName() + " '" + instance().getName() + "' is starting.");
                Ui.info("Follow the startup with:");
                Ui.command("flnet " + parent.kind().bundle() + " logs -f" + nameFlag());
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
                Ui.warn("--volumes permanently deletes ALL data of " + selected.getLabel()
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
            Ui.success("Images of " + selected.getLabel() + " are up to date. Start or recreate the containers with:");
            Ui.command("flnet " + parent.kind().bundle() + " up" + nameFlag());
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
            Ui.warn("This permanently deletes ALL data of " + selected.getLabel()
                    + " (users, cohorts, learning results, databases) and removes its containers" + (images ? " and images." : "."));
            if (purge) {
                Ui.warn("--purge also deletes " + selected.getDirectory() + " with its configuration and secrets.");
            }
            prompter.requireConfirmation(purge ? "Delete all data and the configuration?" : "Delete all data?");
            int code = composeBO.clean(selected, images);
            if (code != 0) {
                return code;
            }
            if (purge) {
                deployments.bo(parent.kind()).delete(selected);
                Ui.success(selected.getKind().displayName() + " '" + selected.getName() + "' is removed completely.");
            } else {
                Ui.success("Removed containers and data. The configuration is kept; start fresh with:");
                Ui.command("flnet " + parent.kind().bundle() + " up" + nameFlag());
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

    /** Runs the export and explains the result; also used by {@code init --compose}. */
    public static void printExport(ComposeBO composeBO, BaseFLNetDeployableInstance instance, String output, boolean keepVariables) {
        composeBO.export(instance, output, keepVariables).ifPresent(file -> {
            Ui.success("Generated " + file);
            if (keepVariables) {
                Ui.info("Values stay in " + instance.getEnvFile() + " and " + instance.getSecretsDirectory()
                        + ", which must exist where the file is used.");
            } else {
                Ui.warn("The file contains all secrets of this deployment in plain text (readable by your user only). "
                        + "Do not commit or share it; use --keep-variables for a shareable version.");
            }
            Ui.info("Bind mounts point to " + instance.getDirectory() + ", so use the file on this machine:");
            Ui.command("docker compose -f " + file + " up -d");
        });
    }
}

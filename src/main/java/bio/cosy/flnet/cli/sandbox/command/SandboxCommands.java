package bio.cosy.flnet.cli.sandbox.command;

import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.base.deployment.FLNetClientDeployment;
import bio.cosy.flnet.cli.deploy.InstanceReportHelper;
import bio.cosy.flnet.cli.deploy.bo.ComposeBO;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.helper.InteractionOptions;
import bio.cosy.flnet.cli.helper.ProcessHelper;
import bio.cosy.flnet.cli.helper.Prompter;
import bio.cosy.flnet.cli.sandbox.FLNetSandbox;
import bio.cosy.flnet.cli.sandbox.bo.FLNetSandboxBO;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

public final class SandboxCommands {

    private static final String MEMBER = "Member of the sandbox: platform, c1, c2, ... (see 'status').";

    private SandboxCommands() {
    }

    static void printDatasetNotice(FLNetSandboxBO sandboxBO) {
        ConsoleHelper.blank();
        ConsoleHelper.info("About the test data:");
        sandboxBO.datasetNotice().forEach(line -> ConsoleHelper.info("  " + line));
    }

    abstract static class SandboxCommandBase implements Callable<Integer> {

        @Inject
        FLNetSandboxBO sandboxBO;

        @Inject
        ComposeBO composeBO;

        /** Runs a compose action on every member and stops at the first failure. */
        int onEach(List<? extends BaseFLNetDeployableInstance> members, java.util.function.ToIntFunction<BaseFLNetDeployableInstance> action) {
            for (BaseFLNetDeployableInstance member : members) {
                ConsoleHelper.heading(member.getName());
                int code = action.applyAsInt(member);
                if (code != 0) {
                    return code;
                }
            }
            return 0;
        }
    }

    @Command(name = "up", description = {"Start (or apply configuration changes to) the platform and all clients in the background.",
            "Pulls the images first, except the ones replaced with 'flnet sandbox images set'."})
    public static class Up extends SandboxCommandBase {

        @Option(names = "--no-pull", description = "Do not pull; use the images that are on this machine.")
        boolean noPull;

        @Override
        public Integer call() {
            FLNetSandbox sandbox = sandboxBO.require();
            if (!noPull) {
                ConsoleHelper.heading("Pulling images");
                for (String image : sandboxBO.pullableImages(sandbox)) {
                    int code = ProcessHelper.runInteractive(null, List.of("docker", "pull", image));
                    if (code != 0) {
                        ConsoleHelper.error("Could not pull " + image + ". Private images need 'docker login'; a self-built image "
                                + "is registered with 'flnet sandbox images set <service> <image>'.");
                        return code;
                    }
                }
            }
            int code = onEach(sandbox.getMembers(), member -> composeBO.up(member, false));
            if (code != 0) {
                return code;
            }
            ConsoleHelper.blank();
            ConsoleHelper.success("The sandbox is starting. Platform: " + sandbox.getPlatform().getAddress());
            ConsoleHelper.info("The first start takes a few minutes. Follow it with:");
            ConsoleHelper.command("flnet sandbox logs platform -f");
            ConsoleHelper.info("Then import the test data:");
            ConsoleHelper.command("flnet sandbox seed");
            return 0;
        }
    }

    @Command(name = "status", description = "Show the members of the sandbox with their address, ports and state.")
    public static class Status extends SandboxCommandBase {

        @Override
        public Integer call() {
            FLNetSandbox sandbox = sandboxBO.require();
            InstanceReportHelper.table(composeBO, sandbox.getMembers());
            ConsoleHelper.blank();
            ConsoleHelper.info("Folder: " + sandbox.getDirectory() + " (see README.md there)");
            if (!sandbox.getImages().isEmpty()) {
                ConsoleHelper.info(sandbox.getImages().size() + " replaced image(s), see 'flnet sandbox images'.");
            }
            return 0;
        }
    }

    @Command(name = "seed", description = {"Fill the sandbox with test data: upload the schema to the platform, then create a cohort and a connector",
            "on each client and import its data file. The sandbox must be running. Needs python3 with the packages of seed/requirements.txt."})
    public static class Seed extends SandboxCommandBase {

        @Override
        public Integer call() {
            FLNetSandbox sandbox = sandboxBO.require();
            if (ProcessHelper.probe("python3", "--version").isEmpty()) {
                throw new CliException("python3 is needed to import the test data. Install it, or see " + sandbox.getReadme()
                        + " for what the import does.", CliException.ENVIRONMENT);
            }
            printDatasetNotice(sandboxBO);
            Map<FLNetClientDeployment, String> data = sandboxBO.seedData(sandbox);
            sandbox.getClients().stream().filter(client -> !data.containsKey(client))
                    .forEach(client -> ConsoleHelper.warn("There is no data file for client " + client.getName() + "; it stays empty."));
            for (List<String> command : sandboxBO.seedCommands(sandbox)) {
                ConsoleHelper.heading(command.get(2));
                int code = ProcessHelper.runInteractive(sandbox.getSeedDirectory(), command);
                if (code != 0) {
                    ConsoleHelper.error("The import failed. Common reasons:");
                    ConsoleHelper.info("  - the sandbox is not completely up yet (e.g. a 502): check 'flnet sandbox status', wait and run it again");
                    ConsoleHelper.info("  - a python package is missing: pip install -r " + sandbox.getSeedDirectory().resolve("requirements.txt"));
                    ConsoleHelper.info("  - an error on the very first run: run it a second time");
                    return code;
                }
            }
            ConsoleHelper.blank();
            ConsoleHelper.success("The test data is imported. Open " + sandbox.getPlatform().getAddress() + " and run a query.");
            return 0;
        }
    }

    @Command(name = "logs", description = "Show the service logs of one member.")
    public static class Logs extends SandboxCommandBase {

        @Parameters(index = "0", paramLabel = "<member>", description = MEMBER)
        String member;

        @Option(names = {"-f", "--follow"}, description = "Follow the log output.")
        boolean follow;

        @Option(names = "--tail", paramLabel = "<n>", defaultValue = "200", description = "Lines per service to show. Default: ${DEFAULT-VALUE}.")
        String tail;

        @Parameters(index = "1..*", paramLabel = "<service>", description = "Only these services.")
        List<String> services = new ArrayList<>();

        @Override
        public Integer call() {
            return composeBO.logs(sandboxBO.require().member(member), tail, follow, services);
        }
    }

    @Command(name = "restart", description = {"Restart the containers of one member as they are.",
            "New images or configuration need 'flnet sandbox up' instead, which recreates changed containers."})
    public static class Restart extends SandboxCommandBase {

        @Parameters(index = "0", paramLabel = "<member>", description = MEMBER)
        String member;

        @Parameters(index = "1..*", paramLabel = "<service>", description = "Only these services.")
        List<String> services = new ArrayList<>();

        @Override
        public Integer call() {
            return composeBO.restart(sandboxBO.require().member(member), services);
        }
    }

    @Command(name = "stop", description = "Stop the containers of one member without removing them. Continue with 'up' or 'restart'.")
    public static class Stop extends SandboxCommandBase {

        @Parameters(index = "0", paramLabel = "<member>", description = MEMBER)
        String member;

        @Parameters(index = "1..*", paramLabel = "<service>", description = "Only these services.")
        List<String> services = new ArrayList<>();

        @Override
        public Integer call() {
            return composeBO.stop(sandboxBO.require().member(member), services);
        }
    }

    @Command(name = "down", description = "Stop the whole sandbox. Data is kept unless --volumes is given.")
    public static class Down extends SandboxCommandBase {

        @Option(names = {"-v", "--volumes"}, description = "Also delete all volumes, i.e. ALL data of the sandbox.")
        boolean volumes;

        @Mixin
        InteractionOptions interaction;

        @Inject
        Prompter prompter;

        @Override
        public Integer call() {
            FLNetSandbox sandbox = sandboxBO.require();
            if (volumes) {
                prompter.configure(interaction);
                ConsoleHelper.warn("--volumes permanently deletes ALL data of the sandbox (users, cohorts, learning results, databases).");
                prompter.requireConfirmation("Delete all data?");
            }
            return onEach(sandbox.getMembers().reversed(), member -> composeBO.down(member, volumes));
        }
    }

    @Command(name = "clean",
            description = {"Remove the containers, networks and volumes (ALL data) of the sandbox for a fresh start.",
                    "With --purge also delete its folder (configuration, secrets, seed data), so nothing of it is left."})
    public static class Clean extends SandboxCommandBase {

        @Option(names = "--purge", description = "Also delete the sandbox folder.")
        boolean purge;

        @Mixin
        InteractionOptions interaction;

        @Inject
        Prompter prompter;

        @Override
        public Integer call() {
            FLNetSandbox sandbox = sandboxBO.require();
            prompter.configure(interaction);
            ConsoleHelper.warn("This permanently deletes ALL data of the sandbox and removes its containers.");
            if (purge) {
                ConsoleHelper.warn("--purge also deletes " + sandbox.getDirectory() + ".");
            }
            prompter.requireConfirmation(purge ? "Delete all data and the sandbox folder?" : "Delete all data?");
            int code = onEach(sandbox.getMembers().reversed(), member -> composeBO.clean(member, false));
            if (code != 0) {
                return code;
            }
            ConsoleHelper.blank();
            if (purge) {
                sandboxBO.delete(sandbox);
                ConsoleHelper.success("The sandbox is removed completely.");
            } else {
                ConsoleHelper.success("Removed containers and data. The configuration is kept; start fresh with:");
                ConsoleHelper.command("flnet sandbox up");
            }
            return 0;
        }
    }
}

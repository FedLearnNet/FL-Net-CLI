package bio.cosy.flnet.cli.sandbox.command;

import bio.cosy.flnet.cli.base.deployment.FLNetClientDeployment;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.sandbox.FLNetSandbox;
import bio.cosy.flnet.cli.sandbox.bo.FLNetSandboxBO;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command(name = "init",
        description = {
                "Create or reconfigure the sandbox: a platform and clients that are already connected to each other.",
                "Asks nothing. Re-running it applies new values and the deployment files of this CLI; secrets and data are kept."
        },
        footer = {
                "",
                "Examples:",
                "  flnet sandbox init",
                "  flnet sandbox init --clients 3 --port 9000"
        })
public class SandboxInitCommand implements Callable<Integer> {

    @Option(names = "--clients", paramLabel = "<n>",
            description = "Number of clients. Default: ${bundle:flnet.sandbox.clients}. Test data exists for two.")
    Integer clients;

    @Option(names = "--port", paramLabel = "<port>",
            description = "Port of the platform; the clients get the next free ports. Default: ${bundle:flnet.sandbox.port}.")
    Integer port;

    @Option(names = "--relay-port", paramLabel = "<port>",
            description = "TCP port of the platform relay. Default: ${bundle:flnet.sandbox.relay-port}.")
    Integer relayPort;

    @Option(names = "--image-tag", paramLabel = "<tag>",
            description = "Tag of the FL-Net images. Default: ${bundle:flnet.sandbox.image-tag}.")
    String imageTag;

    @Inject
    FLNetSandboxBO sandboxBO;

    @Override
    public Integer call() {
        ConsoleHelper.heading("FL-Net sandbox setup");
        boolean existed = sandboxBO.find().isPresent();
        FLNetSandbox sandbox = sandboxBO.configure(clients, port, relayPort, imageTag);
        ConsoleHelper.success((existed ? "Reconfigured" : "Created") + " the sandbox in " + sandbox.getDirectory());
        ConsoleHelper.success("Platform: " + sandbox.getPlatform().getAddress() + " (relay on port " + sandbox.getPlatform().getRelayPort() + ")");
        for (FLNetClientDeployment client : sandbox.getClients()) {
            ConsoleHelper.success("Client " + client.getName() + ": " + client.getAddress());
        }
        ConsoleHelper.info("Log in everywhere with user '" + FLNetSandboxBO.TEST_USER + "' and password '" + FLNetSandboxBO.TEST_PASSWORD + "'.");
        ConsoleHelper.blank();
        sandboxBO.securityNotice(sandbox).forEach(ConsoleHelper::warn);
        SandboxCommands.printDatasetNotice(sandboxBO);

        ConsoleHelper.heading("Next steps");
        ConsoleHelper.info("Start everything (the first start takes a few minutes):");
        ConsoleHelper.command("flnet sandbox up");
        ConsoleHelper.info("Then import the test data (needs python3):");
        ConsoleHelper.command("flnet sandbox seed");
        ConsoleHelper.info("Developing FL-Net? Test a self-built image of a service with:");
        ConsoleHelper.command("flnet sandbox images set <service> <image>");
        ConsoleHelper.info("How to run a federated learning, and how to use the sandbox without flnet: " + sandbox.getReadme());
        return 0;
    }
}

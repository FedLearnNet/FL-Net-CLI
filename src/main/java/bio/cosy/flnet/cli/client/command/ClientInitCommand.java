package bio.cosy.flnet.cli.client.command;

import bio.cosy.flnet.cli.base.deployment.FLNetClientDeployment;
import bio.cosy.flnet.cli.client.ClientInitMode;
import bio.cosy.flnet.cli.client.bo.ClientCertificateBO;
import bio.cosy.flnet.cli.client.bo.FLNetClientDeploymentBO;
import bio.cosy.flnet.cli.client.config.ClientConfig;
import bio.cosy.flnet.cli.client.questionnaire.ClientQuestionnaire;
import bio.cosy.flnet.cli.deploy.command.BaseInitCommand;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.nio.file.Path;
import java.util.List;

@Command(name = "init",
        description = {
                "Create or reconfigure a FL-Net Client deployment directory.",
                "Asks for every setting that is not given as a flag. Re-running it reconfigures an existing client.",
                "Several clients can run on one machine: give each a --name, ports are chosen to not collide."
        },
        footer = {
                "",
                "Examples:",
                "  flnet client init",
                "  flnet client init --no-input --network flnet --platform-username alice \\",
                "      --platform-password-file ./password.txt --listen localhost --port 8250",
                "  FLNET_PLATFORM_PASSWORD=... flnet client init --no-input --network custom \\",
                "      --platform-url https://fl.example.org --platform-relay-port 9150 --platform-username site-a",
                "  flnet client init --domain https://flnet.internal --ssl self-signed --ip 10.0.0.5 --days 730"
        })
public class ClientInitCommand extends BaseInitCommand {

    @Mixin
    ClientConfig given;

    @Inject
    ClientQuestionnaire questionnaire;

    @Inject
    FLNetClientDeploymentBO clientBO;

    @Inject
    ClientCertificateBO certificates;

    @Override
    public Integer call() {
        FLNetClientDeployment client = prepare(clientBO, given);
        ClientInitMode mode = questionnaire.mode(given, client);
        questionnaire.ask(given, client, mode);
        write(clientBO, client, given);
        if (given.getCreateCertificate()) {
            certificates.create(client, given.getCertificate());
        }

        List<String> lines = clientBO.nextSteps(client, mode);
        nextSteps(lines);
        Path saved = clientBO.saveInstructions(client, lines);
        if (saved != null) {
            ConsoleHelper.info("(Saved to " + saved + ")");
        }
        return 0;
    }
}

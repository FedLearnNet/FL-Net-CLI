package bio.cosy.flnet.cli.client.command;

import bio.cosy.flnet.cli.base.deployment.FLNetClientDeployment;
import bio.cosy.flnet.cli.client.bo.ClientCertificateBO;
import bio.cosy.flnet.cli.client.bo.FLNetClientDeploymentBO;
import bio.cosy.flnet.cli.client.config.CertificateConfig;
import bio.cosy.flnet.cli.client.questionnaire.CertificateQuestionnaire;
import bio.cosy.flnet.cli.deploy.command.InstanceOptions;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.helper.InteractionOptions;
import bio.cosy.flnet.cli.helper.Prompter;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.util.concurrent.Callable;

@Command(name = "certs",
        description = {
                "Create a self-signed certificate for a client configured with '--ssl self-signed'.",
                "Defaults are taken from the client's domain. Requires openssl."
        },
        footer = {"", "Example:", "  flnet client certs --no-input --dns flnet.internal --ip 10.0.0.5 --days 730"})
public class ClientCertsCommand implements Callable<Integer> {

    @Mixin
    InstanceOptions instanceOptions;

    @Mixin
    CertificateConfig given;

    @Option(names = "--force", description = "Overwrite an existing certificate.")
    boolean force;

    @Mixin
    InteractionOptions interaction;

    @Inject
    Prompter prompter;

    @Inject
    FLNetClientDeploymentBO clientBO;

    @Inject
    ClientCertificateBO certificates;

    @Inject
    CertificateQuestionnaire questionnaire;

    @Override
    public Integer call() {
        prompter.configure(interaction);
        FLNetClientDeployment client = clientBO.select(instanceOptions, prompter);
        certificates.requireOpenssl();
        if (Files.exists(client.getSelfSignedCertificate()) && !force) {
            ConsoleHelper.warn("A certificate already exists: " + client.getSelfSignedCertificate());
            prompter.requireConfirmation("Replace it?");
        }
        questionnaire.ask(given, client);
        certificates.create(client, given);

        ConsoleHelper.info("Inspect it with:");
        ConsoleHelper.command("openssl x509 -in " + client.getSelfSignedCertificate() + " -text -noout");
        ConsoleHelper.info("If the client is running, reload nginx with 'docker compose exec reverse-proxy-encrypted nginx -s reload', otherwise:");
        ConsoleHelper.command("flnet client up" + clientBO.nameFlag(client));
        return 0;
    }
}

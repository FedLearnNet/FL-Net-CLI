package bio.cosy.flnet.cli.client;

import bio.cosy.flnet.cli.config.FLNetCliConfig;
import bio.cosy.flnet.cli.deploy.InstanceOptions;
import bio.cosy.flnet.cli.base.FLNetClientDeployment;
import bio.cosy.flnet.cli.support.InteractionOptions;
import bio.cosy.flnet.cli.support.Net;
import bio.cosy.flnet.cli.support.Prompter;
import bio.cosy.flnet.cli.support.Prompter.Validator;
import bio.cosy.flnet.cli.support.Ui;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/** Asks what a self-signed client certificate is for (port of {@code create_self_signed_certs.py}); {@link FLNetClientDeploymentBO} creates it. */
@Command(name = "certs",
        description = {
                "Create a self-signed certificate for a client configured with '--ssl self-signed'.",
                "Defaults are taken from the client's domain. Requires openssl."
        },
        footer = {"", "Example:", "  flnet client certs --no-input --dns flnet.internal --ip 10.0.0.5 --days 730"})
public class ClientCertsCommand implements Callable<Integer> {

    @Mixin
    InstanceOptions instanceOptions;

    @Option(names = "--cn", paramLabel = "<name>", description = "Common name. Default: the client domain.")
    String commonName;

    @Option(names = "--dns", paramLabel = "<host>", split = ",", description = "DNS names the certificate is valid for (repeatable or comma separated).")
    List<String> dnsNames;

    @Option(names = "--ip", paramLabel = "<ip>", split = ",", description = "IP addresses the certificate is valid for (repeatable or comma separated).")
    List<String> ipAddresses;

    @Option(names = "--country", paramLabel = "<CC>", description = "Two letter country code (optional).")
    String country;

    @Option(names = "--state", description = "State or province (optional).")
    String state;

    @Option(names = "--city", description = "City (optional).")
    String city;

    @Option(names = "--org", description = "Organization (optional).")
    String organization;

    @Option(names = "--unit", description = "Organizational unit (optional).")
    String unit;

    @Option(names = "--days", paramLabel = "<n>", description = "Validity in days. Default: ${bundle:flnet.client.certificate.days}.")
    Integer days;

    @Option(names = "--force", description = "Overwrite an existing certificate.")
    boolean force;

    @Mixin
    InteractionOptions interaction;

    @Inject
    Prompter prompter;

    @Inject
    FLNetClientDeploymentBO clientBO;

    @Inject
    FLNetCliConfig config;

    @Override
    public Integer call() {
        prompter.configure(interaction);
        FLNetClientDeployment client = clientBO.select(instanceOptions, prompter);
        String openssl = clientBO.requireOpenssl();
        if (Files.exists(client.getSelfSignedCertificate()) && !force) {
            Ui.warn("A certificate already exists: " + client.getSelfSignedCertificate());
            prompter.requireConfirmation("Replace it?");
        }

        Ui.heading("Self-signed certificate");
        Ui.info("Only the common name is required; empty answers leave optional fields out.");
        CertificateRequest request = new CertificateRequest();
        request.setCommonName(prompter.text("--cn", commonName, "Common name (primary hostname or IP)", client.getServerName(), Validator.NOT_EMPTY));
        request.setCountry(prompter.text("--country", country, "Country code (2 letters)", "",
                value -> value.isEmpty() || value.matches("[A-Za-z]{2}") ? null : "Use exactly two letters, e.g. DE."));
        request.setState(prompter.text("--state", state, "State or province", "", Validator.ANY));
        request.setCity(prompter.text("--city", city, "City", "", Validator.ANY));
        request.setOrganization(prompter.text("--org", organization, "Organization", "", Validator.ANY));
        request.setOrganizationalUnit(prompter.text("--unit", unit, "Organizational unit", "", Validator.ANY));

        Ui.info("Browsers only trust the Subject Alternative Names (SANs), the common name alone is not enough.");
        boolean cnIsIp = Net.isIpv4(request.getCommonName());
        request.setDnsNames(list(prompter.text("--dns", join(dnsNames), "DNS names, comma separated",
                cnIsIp ? "" : request.getCommonName(), Validator.ANY)));
        request.setIpAddresses(list(prompter.text("--ip", join(ipAddresses), "IP addresses, comma separated",
                cnIsIp ? request.getCommonName() : "",
                value -> list(value).stream().filter(ip -> !Net.isIpv4(ip)).findFirst().map(ip -> "'" + ip + "' is not an IPv4 address.").orElse(null))));
        request.setDays(prompter.integer("--days", days, "Validity in days", config.client().certificate().days(), 1));

        Ui.info("Generating a 4096 bit RSA key with " + openssl + " ...");
        clientBO.createSelfSignedCertificate(client, request);

        Ui.success("Certificate: " + client.getSelfSignedCertificate());
        Ui.success("Private key: " + client.getSelfSignedPrivateKey());
        Ui.info("Browsers will warn about the self-signed certificate until you add it to the OS/browser trust store.");
        Ui.info("Inspect it with:");
        Ui.command("openssl x509 -in " + client.getSelfSignedCertificate() + " -text -noout");
        Ui.info("If the client is running, reload nginx with 'docker compose exec reverse-proxy-encrypted nginx -s reload', otherwise:");
        Ui.command("flnet client up" + clientBO.nameFlag(client));
        return 0;
    }

    private static List<String> list(String value) {
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static String join(List<String> values) {
        return values == null ? null : String.join(",", values);
    }
}

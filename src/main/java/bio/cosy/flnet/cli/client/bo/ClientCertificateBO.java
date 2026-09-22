package bio.cosy.flnet.cli.client.bo;

import bio.cosy.flnet.cli.base.deployment.FLNetClientDeployment;
import bio.cosy.flnet.cli.client.config.CertificateConfig;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.helper.FilePermissionHelper;
import bio.cosy.flnet.cli.helper.ProcessHelper;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;

@ApplicationScoped
public class ClientCertificateBO {

    public Optional<String> opensslVersion() {
        return ProcessHelper.probe("openssl", "version");
    }

    public String requireOpenssl() {
        return opensslVersion().orElseThrow(() -> new CliException("openssl is not installed or not on the PATH "
                + "(e.g. 'sudo apt install openssl' or 'brew install openssl').", CliException.ENVIRONMENT));
    }

    public void create(FLNetClientDeployment client, CertificateConfig certificate) {
        Path template = client.getSelfSignedDirectory().resolve("san.cnf.template");
        if (!Files.isRegularFile(template)) {
            throw new CliException("No client deployment found in " + client.getDirectory() + ". Run 'flnet client init' first.",
                    CliException.ENVIRONMENT);
        }
        if (certificate.getDnsNames().isEmpty() && certificate.getIpAddresses().isEmpty()) {
            throw CliException.usage("At least one DNS name (--dns) or IP address (--ip) is required.");
        }
        ConsoleHelper.info("Generating a 4096 bit RSA key with " + requireOpenssl() + " ...");
        Path sanCnf = client.getSelfSignedDirectory().resolve("san.cnf");
        writeSanConfig(template, sanCnf, certificate);
        int code = ProcessHelper.runInteractive(null, List.of("openssl", "req", "-x509", "-newkey", "rsa", "-nodes",
                "-keyout", client.getSelfSignedPrivateKey().toString(), "-out", client.getSelfSignedCertificate().toString(),
                "-days", String.valueOf(certificate.getDays()), "-config", sanCnf.toString()));
        if (code != 0) {
            throw new CliException("openssl failed with exit code " + code + ".", code);
        }
        FilePermissionHelper.set(client.getSelfSignedPrivateKey(), FilePermissionHelper.READABLE);
        FilePermissionHelper.set(client.getSelfSignedCertificate(), FilePermissionHelper.READABLE);
        ConsoleHelper.success("Certificate: " + client.getSelfSignedCertificate());
        ConsoleHelper.success("Private key: " + client.getSelfSignedPrivateKey());
        ConsoleHelper.info("Browsers will warn about the self-signed certificate until you add it to the OS/browser trust store.");
    }

    static void writeSanConfig(Path template, Path target, CertificateConfig certificate) {
        try {
            String content = Files.readString(template, StandardCharsets.UTF_8);
            List<String> dn = new ArrayList<>(List.of("[dn]"));
            addIfPresent(dn, "C  = ", certificate.getCountry().toUpperCase(Locale.ROOT));
            addIfPresent(dn, "ST = ", certificate.getState());
            addIfPresent(dn, "L  = ", certificate.getCity());
            addIfPresent(dn, "O  = ", certificate.getOrganization());
            addIfPresent(dn, "OU = ", certificate.getOrganizationalUnit());
            dn.add("CN = " + certificate.getCommonName());
            content = content.replaceFirst("(?s)\\[dn\\].*?(?=\\n\\[)", Matcher.quoteReplacement(String.join("\n", dn)));

            List<String> alt = new ArrayList<>(List.of("[alt_names]"));
            for (int i = 0; i < certificate.getDnsNames().size(); i++) {
                alt.add("DNS." + (i + 1) + " = " + certificate.getDnsNames().get(i));
            }
            for (int i = 0; i < certificate.getIpAddresses().size(); i++) {
                alt.add("IP." + (i + 1) + "  = " + certificate.getIpAddresses().get(i));
            }
            content = content.replaceFirst("(?s)\\[alt_names\\].*$", Matcher.quoteReplacement(String.join("\n", alt) + "\n"));
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + target, e);
        }
    }

    private static void addIfPresent(List<String> lines, String prefix, String value) {
        if (value != null && !value.isEmpty()) {
            lines.add(prefix + value);
        }
    }
}

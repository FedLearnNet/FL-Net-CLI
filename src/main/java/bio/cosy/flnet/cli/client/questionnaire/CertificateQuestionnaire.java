package bio.cosy.flnet.cli.client.questionnaire;

import bio.cosy.flnet.cli.base.deployment.FLNetClientDeployment;
import bio.cosy.flnet.cli.client.config.CertificateConfig;
import bio.cosy.flnet.cli.deploy.questionnaire.BaseQuestionnaire;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.helper.NetworkHelper;
import bio.cosy.flnet.cli.helper.Prompter.Validator;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Arrays;
import java.util.List;

@ApplicationScoped
public class CertificateQuestionnaire extends BaseQuestionnaire {

    public void ask(CertificateConfig given, FLNetClientDeployment client) {
        section("Self-signed certificate", "Only the common name is required; empty answers leave optional fields out.");
        given.setCommonName(prompter.text("--cn", given.getCommonName(), "Common name (primary hostname or IP)",
                client.getServerName(), Validator.NOT_EMPTY));
        given.setCountry(prompter.text("--country", given.getCountry(), "Country code (2 letters)", "",
                value -> value.isEmpty() || value.matches("[A-Za-z]{2}") ? null : "Use exactly two letters, e.g. DE."));
        given.setState(prompter.text("--state", given.getState(), "State or province", "", Validator.ANY));
        given.setCity(prompter.text("--city", given.getCity(), "City", "", Validator.ANY));
        given.setOrganization(prompter.text("--org", given.getOrganization(), "Organization", "", Validator.ANY));
        given.setOrganizationalUnit(prompter.text("--unit", given.getOrganizationalUnit(), "Organizational unit", "", Validator.ANY));

        ConsoleHelper.info("Browsers only trust the Subject Alternative Names (SANs), the common name alone is not enough.");
        boolean cnIsIp = NetworkHelper.isIpv4(given.getCommonName());
        given.setDnsNames(list(prompter.text("--dns", join(given.getDnsNames()), "DNS names, comma separated",
                cnIsIp ? "" : given.getCommonName(), Validator.ANY)));
        given.setIpAddresses(list(prompter.text("--ip", join(given.getIpAddresses()), "IP addresses, comma separated",
                cnIsIp ? given.getCommonName() : "", CertificateQuestionnaire::validateIps)));
        given.setDays(prompter.integer("--days", given.getDays(), "Validity in days", config.client().certificate().days(), 1));
    }

    private static String validateIps(String value) {
        return list(value).stream().filter(ip -> !NetworkHelper.isIpv4(ip)).findFirst()
                .map(ip -> "'" + ip + "' is not an IPv4 address.").orElse(null);
    }

    private static List<String> list(String value) {
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static String join(List<String> values) {
        return values == null ? null : String.join(",", values);
    }
}

package bio.cosy.flnet.cli.client.config;

import lombok.Getter;
import lombok.Setter;
import picocli.CommandLine.Option;

import java.util.List;

@Getter
@Setter
public class CertificateConfig {

    @Option(names = "--cn", paramLabel = "<name>", description = "Certificate common name. Default: the client domain.")
    private String commonName;

    @Option(names = "--dns", paramLabel = "<host>", split = ",", description = "DNS names the certificate is valid for (repeatable or comma separated).")
    private List<String> dnsNames;

    @Option(names = "--ip", paramLabel = "<ip>", split = ",", description = "IP addresses the certificate is valid for (repeatable or comma separated).")
    private List<String> ipAddresses;

    @Option(names = "--country", paramLabel = "<CC>", description = "Certificate: two letter country code (optional).")
    private String country;

    @Option(names = "--state", description = "Certificate: state or province (optional).")
    private String state;

    @Option(names = "--city", description = "Certificate: city (optional).")
    private String city;

    @Option(names = "--org", description = "Certificate: organization (optional).")
    private String organization;

    @Option(names = "--unit", description = "Certificate: organizational unit (optional).")
    private String organizationalUnit;

    @Option(names = "--days", paramLabel = "<n>", description = "Certificate validity in days. Default: ${bundle:flnet.client.certificate.days}.")
    private Integer days;
}

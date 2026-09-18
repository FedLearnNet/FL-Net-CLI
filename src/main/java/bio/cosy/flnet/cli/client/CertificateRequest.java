package bio.cosy.flnet.cli.client;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** What a self-signed client certificate is issued for; empty optional fields are left out. */
@Getter
@Setter
public class CertificateRequest {

    private String commonName;
    private String country = "";
    private String state = "";
    private String city = "";
    private String organization = "";
    private String organizationalUnit = "";
    private List<String> dnsNames = new ArrayList<>();
    private List<String> ipAddresses = new ArrayList<>();
    private int days;
}

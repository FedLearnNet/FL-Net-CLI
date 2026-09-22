package bio.cosy.flnet.cli.platform.config;

import bio.cosy.flnet.cli.helper.SecretHelper;
import bio.cosy.flnet.cli.helper.WebAddress;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.IpAddress;

@Getter
@Setter
public class PersistentPlatformConfig {

    public static final int RECOMMENDED_MIN_CLIENTS = 3;

    @NotNull(message = "The domain is required.")
    private WebAddress domain;
    @NotNull(message = "The bind IP is required.")
    @IpAddress(type = IpAddress.Type.IPv4, message = "The bind IP must be an IPv4 address.")
    private String bindIp;
    private int nginxPort;
    private int relayPort;
    @Min(value = 1, message = "At least one client is required to start a learning.")
    private int minClients;
    private boolean clientAuth = true;

    // generated secrets
    private String neo4jPassword;
    private String datamodelerClientSecret;
    private String globalLearningClientSecret;
    private String globalLearningDbPassword;

    public boolean isLocalOnly() {
        return domain != null && "localhost".equals(domain.host());
    }

    public boolean isBehindReverseProxy() {
        return "127.0.0.1".equals(bindIp);
    }

    public boolean isBelowRecommendedMinClients() {
        return minClients < RECOMMENDED_MIN_CLIENTS;
    }

    public void setBindIp(String bindIp) {
        this.bindIp = "localhost".equals(bindIp) ? "127.0.0.1" : bindIp;
    }

    public void generateSecrets(int length) {
        neo4jPassword = SecretHelper.generate(length);
        datamodelerClientSecret = SecretHelper.generate(length);
        globalLearningClientSecret = SecretHelper.generate(length);
        globalLearningDbPassword = SecretHelper.generate(length);
    }

    public boolean hasSecrets() {
        return neo4jPassword != null && datamodelerClientSecret != null
                && globalLearningClientSecret != null && globalLearningDbPassword != null;
    }
}

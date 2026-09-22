package bio.cosy.flnet.cli.base.deployment;

import bio.cosy.flnet.cli.helper.WebAddress;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PlatformKeycloakConfig {

    @NotNull(message = "The platform address is required.")
    private WebAddress platformAddress;
    private String keycloakRealmPath;

    public String getPlatformUrl() {
        return platformAddress == null ? "" : platformAddress.toString();
    }

    public String getKeycloakUrl() {
        return platformAddress == null ? "" : platformAddress + keycloakRealmPath;
    }

    public String getWsUrl() {
        return platformAddress == null ? "" : (platformAddress.isHttps() ? "wss://" : "ws://") + platformAddress.hostWithPort();
    }
}

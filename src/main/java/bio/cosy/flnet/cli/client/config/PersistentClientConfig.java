package bio.cosy.flnet.cli.client.config;

import bio.cosy.flnet.cli.base.deployment.AutoAccess;
import bio.cosy.flnet.cli.base.deployment.PlatformKeycloakConfig;
import bio.cosy.flnet.cli.helper.SecretHelper;
import bio.cosy.flnet.cli.helper.WebAddress;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.Range;

@Getter
@Setter
public class PersistentClientConfig {

    public static final String NO_AUTH_CREDENTIAL = "dummy";

    @Valid
    private final PlatformKeycloakConfig platformKeycloak = new PlatformKeycloakConfig();
    @Range(min = 1, max = 65535, message = "The platform relay port must be between 1 and 65535.")
    private int platformRelayPort;
    private boolean platformAuth = true;
    private boolean federation = true;

    // platform login (outbound credential of local-learning-api, not baked into a volume)
    private String platformUsername;
    private String platformPassword;

    // data access permissions
    private boolean allowAutoStatistics;
    private boolean allowAutoLearning;
    private boolean allowAutoMetrics;
    private boolean defaultPermission;
    private String permissionUser = "";
    private AutoAccess autoStatistics = AutoAccess.NONE;
    private AutoAccess autoMetrics = AutoAccess.NONE;
    private AutoAccess autoLearning = AutoAccess.NONE;
    private boolean allowQueries = true;
    @Min(value = 0, message = "The query retry time cannot be negative.")
    private int queryRetryTime;
    @Min(value = 0, message = "The query sample threshold cannot be negative.")
    private int querySampleThreshold;

    // generated secrets
    private String learningDbPassword;
    private String learningClientSecret;

    public WebAddress getPlatformAddress() {
        return platformKeycloak.getPlatformAddress();
    }

    public void setPlatformAddress(WebAddress platformAddress) {
        platformKeycloak.setPlatformAddress(platformAddress);
    }

    public String getKeycloakRealmPath() {
        return platformKeycloak.getKeycloakRealmPath();
    }

    public void setKeycloakRealmPath(String keycloakRealmPath) {
        platformKeycloak.setKeycloakRealmPath(keycloakRealmPath);
    }

    public String getPlatformKeycloakUrl() {
        return federation ? platformKeycloak.getKeycloakUrl() : "";
    }

    public boolean hasPlatformLogin() {
        return platformUsername != null && !platformUsername.isBlank() && !NO_AUTH_CREDENTIAL.equals(platformUsername);
    }

    public void disableDefaultPermission(int retryTime, int sampleThreshold) {
        defaultPermission = false;
        permissionUser = "";
        autoStatistics = AutoAccess.NONE;
        autoMetrics = AutoAccess.NONE;
        autoLearning = AutoAccess.NONE;
        allowQueries = true;
        queryRetryTime = retryTime;
        querySampleThreshold = sampleThreshold;
    }

    public void setPermissionUser(String permissionUser) {
        this.permissionUser = permissionUser == null ? "" : permissionUser;
    }

    public void generateSecrets(int length) {
        learningDbPassword = SecretHelper.generate(length);
        learningClientSecret = SecretHelper.generate(length);
    }

    public boolean hasSecrets() {
        return learningDbPassword != null && learningClientSecret != null;
    }

    public void clearSecrets() {
        learningDbPassword = null;
        learningClientSecret = null;
    }

    @AssertTrue(message = "The platform requires authentication, so a platform username and password are required.")
    boolean isPlatformLoginComplete() {
        return !platformAuth || (hasPlatformLogin() && platformPassword != null && !platformPassword.isBlank());
    }

    @AssertTrue(message = "Automatic access in the default permission requires allowing automatic permissions for that resource.")
    boolean isAutoAccessAllowed() {
        return (allowAutoStatistics || autoStatistics == AutoAccess.NONE)
                && (allowAutoMetrics || autoMetrics == AutoAccess.NONE)
                && (allowAutoLearning || autoLearning == AutoAccess.NONE);
    }
}

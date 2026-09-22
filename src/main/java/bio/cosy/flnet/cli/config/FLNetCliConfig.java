package bio.cosy.flnet.cli.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.hibernate.validator.constraints.Range;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ConfigMapping(prefix = "flnet")
public interface FLNetCliConfig {

    Optional<String> home();

    String documentationUrl();

    String keycloakRealmPath();

    Map<String, NetworkSettings> networks();

    ImageSettings images();

    SecretSettings secrets();

    ComposeSettings compose();

    ClientSettings client();

    PlatformSettings platform();

    ToolSettings tool();

    interface NetworkSettings {

        @NotBlank(message = "Every network needs a name.")
        String name();

        @NotBlank(message = "Every network needs a platform-url.")
        String platformUrl();

        Optional<List<String>> aliases();

        @Range(min = 1, max = 65535, message = "Ports must be between 1 and 65535.")
        int relayPort();

        boolean clientAuth();

        @WithDefault("fl-net")
        String frontend();

        @WithDefault("100")
        int order();
    }

    interface ImageSettings {

        @WithDefault("latest")
        String tag();

        FrontendImageSettings frontend();
    }

    interface FrontendImageSettings {

        String registry();

        @WithName("default")
        String defaultFrontend();
    }

    interface SecretSettings {

        @WithDefault("64")
        @Min(value = 32, message = "Secrets must have at least 32 characters.")
        int length();

        @WithDefault("16")
        @Min(value = 12, message = "Admin passwords must have at least 12 characters.")
        int adminPasswordLength();
    }

    interface ComposeSettings {

        String generatedFile();
    }

    interface ClientSettings {

        String projectName();

        String defaultNetwork();

        @Range(min = 1, max = 65535, message = "Ports must be between 1 and 65535.")
        int port();

        @Range(min = 1, max = 65535, message = "Ports must be between 1 and 65535.")
        int customRelayPort();

        String keycloakAdminUsername();

        PermissionSettings permission();

        CertificateSettings certificate();
    }

    interface PermissionSettings {

        @PositiveOrZero(message = "Must not be negative.")
        int queryRetryTime();

        @PositiveOrZero(message = "Must not be negative.")
        int querySampleThreshold();
    }

    interface CertificateSettings {

        @Positive(message = "Certificates must be valid for at least one day.")
        int days();
    }

    interface PlatformSettings {

        String projectName();

        String domain();

        String bindIp();

        @Range(min = 1, max = 65535, message = "Ports must be between 1 and 65535.")
        int port();

        @Range(min = 1, max = 65535, message = "Ports must be between 1 and 65535.")
        int relayPort();

        @Positive(message = "At least one client is required to start a learning.")
        int minClients();

        String keycloakAdminUsername();
    }

    interface ToolSettings {

        String sdkVersion();

        String baseImage();

        String platformUrl();

        String appId();

        ToolEnvSettings env();
    }

    interface ToolEnvSettings {

        boolean configSync();

        boolean tracePerformance();

        boolean projectStartup();

        boolean prioLocalConfig();

        boolean sendConsoleLogs();
    }
}

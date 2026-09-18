package bio.cosy.flnet.cli.config;

import bio.cosy.flnet.cli.support.Port;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * All settings of the CLI, documented in {@code application.properties} and validated at startup.
 * Every value can be overridden per machine in {@code ~/.config/flnet/application.properties}, with environment
 * variables ({@code FLNET_CLIENT_PORT=8300}) or system properties.
 */
@ConfigMapping(prefix = "flnet")
public interface FLNetCliConfig {

    /** Root of all instances; unset = {@code ~/fl-net}. Also settable as {@code $FLNET_HOME}. */
    Optional<String> home();

    @WithName("documentation-url")
    String documentationUrl();

    /** Path of the platform Keycloak realm, appended to a platform address. */
    @WithName("keycloak-realm-path")
    String keycloakRealmPath();

    /** Networks offered by {@code flnet client init --network <key>}; the map key is the CLI value. */
    Map<String, NetworkConfig> networks();

    ImagesConfig images();

    SecretsConfig secrets();

    ComposeConfig compose();

    ClientConfig client();

    PlatformConfig platform();

    ToolConfig tool();

    interface NetworkConfig {

        /** Name shown to users. */
        @NotBlank(message = "Every network needs a name.")
        String name();

        @WithName("platform-url")
        @NotBlank(message = "Every network needs a platform-url.")
        String platformUrl();

        /** Further addresses of the same platform, e.g. old domains. */
        Optional<List<String>> aliases();

        @WithName("relay-port")
        @Port
        int relayPort();

        /** Whether the platform requires clients to authenticate (needs a platform account). */
        @WithName("client-auth")
        boolean clientAuth();

        /** Frontend styling, see {@code flnet.images.frontend}. */
        @WithDefault("fl-net")
        String frontend();

        /** Position in lists and prompts. */
        @WithDefault("100")
        int order();
    }

    interface ImagesConfig {

        /** Tag of all FL-Net service images. */
        @WithDefault("latest")
        String tag();

        FrontendImagesConfig frontend();
    }

    interface FrontendImagesConfig {

        /** Registry path; images are {@code <registry>/<global|local>-<frontend>:<tag>}. */
        String registry();

        /** Styling for platforms that are not a configured network. */
        @WithName("default")
        String defaultFrontend();
    }

    interface SecretsConfig {

        /** Length of generated passwords and client secrets (alphanumeric). */
        @WithDefault("64")
        @Min(value = 32, message = "Secrets must have at least 32 characters.")
        int length();

        /** Keycloak admin passwords are typed on first login, so they are shorter. */
        @WithName("admin-password-length")
        @WithDefault("16")
        @Min(value = 12, message = "Admin passwords must have at least 12 characters.")
        int adminPasswordLength();
    }

    interface ComposeConfig {

        /** File written by {@code flnet <kind> compose} inside the deployment directory. */
        @WithName("generated-file")
        String generatedFile();
    }

    interface ClientConfig {

        /** Docker compose project of the instance named 'default'; others get '-<name>' appended. */
        @WithName("project-name")
        String projectName();

        @WithName("default-network")
        String defaultNetwork();

        /** First port offered for the client web access. */
        @Port
        int port();

        /** Relay port offered for a custom (self-deployed) network. */
        @WithName("custom-relay-port")
        @Port
        int customRelayPort();

        @WithName("keycloak-admin-username")
        String keycloakAdminUsername();

        PermissionConfig permission();

        CertificateConfig certificate();
    }

    interface PermissionConfig {

        @WithName("query-retry-time")
        @PositiveOrZero(message = "Must not be negative.")
        int queryRetryTime();

        @WithName("query-sample-threshold")
        @PositiveOrZero(message = "Must not be negative.")
        int querySampleThreshold();
    }

    interface CertificateConfig {

        /** Validity of self-signed certificates in days. */
        @Positive(message = "Certificates must be valid for at least one day.")
        int days();
    }

    interface PlatformConfig {

        @WithName("project-name")
        String projectName();

        String domain();

        @WithName("bind-ip")
        String bindIp();

        /** First port offered for the platform nginx. */
        @Port
        int port();

        /** First port offered for the relay server. */
        @WithName("relay-port")
        @Port
        int relayPort();

        @WithName("min-clients")
        @Positive(message = "At least one client is required to start a learning.")
        int minClients();

        @WithName("keycloak-admin-username")
        String keycloakAdminUsername();
    }

    interface ToolConfig {

        /** FL-Net-Python-Tool-API version pinned in requirements.txt. */
        @WithName("sdk-version")
        String sdkVersion();

        @WithName("base-image")
        String baseImage();

        /** Platform the tool is developed against (.env URLs). */
        @WithName("platform-url")
        String platformUrl();

        @WithName("app-id")
        String appId();

        ToolEnvConfig env();
    }

    /** Defaults of the pyfedappwrap switches written into a tool's .env. */
    interface ToolEnvConfig {

        @WithName("config-sync")
        boolean configSync();

        @WithName("trace-performance")
        boolean tracePerformance();

        @WithName("project-startup")
        boolean projectStartup();

        @WithName("prio-local-config")
        boolean prioLocalConfig();

        @WithName("send-console-logs")
        boolean sendConsoleLogs();
    }
}

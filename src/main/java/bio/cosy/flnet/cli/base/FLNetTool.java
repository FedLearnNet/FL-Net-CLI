package bio.cosy.flnet.cli.base;

import bio.cosy.flnet.cli.support.WebAddress;
import io.quarkus.qute.TemplateData;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Locale;

/**
 * A FL-Net tool project (Python, pyfedappwrap). Holds everything the project is generated from and
 * is the {@code tool} object of the templates in {@code templates/tool}.
 */
@TemplateData
@Getter
@Setter
public class FLNetTool extends BaseFLNet {

    private ToolType toolType = ToolType.ANALYSIS;
    private boolean federated;
    private String description;
    private String sourceUrl;
    @NotBlank(message = "The app id is required.")
    private String appId;
    @NotNull(message = "The platform address is required.")
    private WebAddress platformAddress;
    private String keycloakRealmPath;
    private boolean configSync;
    private boolean tracePerformance;
    private boolean projectStartup;
    private boolean prioLocalConfig;
    private boolean sendConsoleLogs;
    @NotBlank(message = "The SDK version is required.")
    private String sdkVersion;
    @NotBlank(message = "The base image is required.")
    private String baseImage;

    @Override
    public String getTypeLabel() {
        return "tool";
    }

    // ---------------------------------------------------------------- derived values

    /** Python class name, e.g. "random forest-v2" -> "RandomForestV2". */
    public String getClassName() {
        return className(getName());
    }

    /** Directory name and registry slug, e.g. "Random Forest" -> "random-forest". */
    public String getSlug() {
        return slug(getName());
    }

    /** Tool type as written into app.yml, e.g. "PRE_PROCESSING". */
    public String getType() {
        return toolType.name();
    }

    public boolean hasConfigModule() {
        return toolType.hasConfigModule();
    }

    public boolean hasInputs() {
        return !getInputs().isEmpty();
    }

    public List<FLNetToolField> getHyperparams() {
        return toolType.hyperparams(federated);
    }

    public List<FLNetToolField> getInputs() {
        return toolType.inputs();
    }

    public List<FLNetToolField> getOutputs() {
        return toolType.outputs(federated);
    }

    public String getPlatformUrl() {
        return platformAddress.toString();
    }

    public String getWsUrl() {
        return (platformAddress.isHttps() ? "wss://" : "ws://") + platformAddress.hostWithPort();
    }

    public String getKeycloakUrl() {
        return platformAddress + keycloakRealmPath;
    }

    /** The default app id means the tool is not yet linked to the platform. */
    public boolean isLinkedToPlatform(String defaultAppId) {
        return appId != null && !appId.equals(defaultAppId);
    }

    public String getNameYaml() {
        return yamlString(getName());
    }

    public String getDescriptionYaml() {
        return yamlString(description);
    }

    public String getSourceUrlYaml() {
        return sourceUrl == null || sourceUrl.isBlank() ? "null" : yamlString(sourceUrl);
    }

    // ---------------------------------------------------------------- validation

    @AssertTrue(message = "--federated is only supported for analysis tools.")
    boolean isFederationSupported() {
        return !federated || toolType.supportsFederated();
    }

    // ---------------------------------------------------------------- helpers

    public static String className(String name) {
        StringBuilder result = new StringBuilder();
        boolean upperNext = true;
        for (char c : (name == null ? "" : name).toCharArray()) {
            if (!Character.isLetterOrDigit(c) || c > 127) {
                upperNext = true;
            } else if (upperNext) {
                result.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                result.append(c);
            }
        }
        if (result.isEmpty()) {
            return "MyApp";
        }
        return Character.isDigit(result.charAt(0)) ? "App" + result : result.toString();
    }

    public static String slug(String name) {
        String slug = (name == null ? "" : name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "my-tool" : slug;
    }

    /** Double quoted YAML scalar, safe for any user input. */
    public static String yamlString(String value) {
        String text = value == null ? "" : value;
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }
}

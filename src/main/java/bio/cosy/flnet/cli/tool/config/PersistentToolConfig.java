package bio.cosy.flnet.cli.tool.config;

import bio.cosy.flnet.cli.base.tool.FLNetToolField;
import bio.cosy.flnet.cli.base.tool.ToolType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class PersistentToolConfig {

    private ToolType toolType = ToolType.ANALYSIS;
    private boolean federated;
    private String description;
    private String sourceUrl;
    @NotBlank(message = "The app id is required.")
    private String appId;
    private boolean configSync;
    private boolean tracePerformance;
    private boolean projectStartup;
    private boolean prioLocalConfig;
    private boolean sendConsoleLogs;
    @NotBlank(message = "The SDK version is required.")
    private String sdkVersion;
    @NotBlank(message = "The base image is required.")
    private String baseImage;

    public String getType() {
        return toolType.name();
    }

    public boolean hasConfigModule() {
        return toolType.hasConfigModule();
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

    public boolean isLinkedToPlatform(String defaultAppId) {
        return appId != null && !appId.equals(defaultAppId);
    }

    @AssertTrue(message = "--federated is only supported for analysis tools.")
    boolean isFederationSupported() {
        return !federated || toolType.supportsFederated();
    }
}

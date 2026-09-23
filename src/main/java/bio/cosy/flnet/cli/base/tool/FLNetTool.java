package bio.cosy.flnet.cli.base.tool;

import bio.cosy.flnet.cli.base.BaseFLNet;
import bio.cosy.flnet.cli.base.deployment.PlatformKeycloakConfig;
import bio.cosy.flnet.cli.tool.config.PersistentToolConfig;
import io.quarkus.qute.TemplateData;
import jakarta.validation.Valid;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Delegate;

import java.util.Locale;

@TemplateData
@Getter
@Setter
public class FLNetTool extends BaseFLNet {

    @Valid
    @Delegate
    private final PersistentToolConfig config = new PersistentToolConfig();
    @Valid
    @Delegate
    private final PlatformKeycloakConfig platformKeycloak = new PlatformKeycloakConfig();

    @Override
    public String getTypeLabel() {
        return "tool";
    }


    public String getClassName() {
        return className(getName());
    }

    public String getSlug() {
        return slug(getName());
    }

    public String getNameYaml() {
        return yamlString(getName());
    }

    public String getDescriptionYaml() {
        return yamlString(getDescription());
    }

    public String getSourceUrlYaml() {
        return yamlString(getSourceUrl());
    }

    public boolean hasInputs() {
        return !getInputs().isEmpty();
    }


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

    public static String yamlString(String value) {
        String text = value == null ? "" : value;
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }
}

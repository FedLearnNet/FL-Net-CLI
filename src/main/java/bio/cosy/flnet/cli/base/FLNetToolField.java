package bio.cosy.flnet.cli.base;

import io.quarkus.qute.TemplateData;
import lombok.Value;

/**
 * A hyperparameter, input or output of a tool, rendered into app.yml and config.py.
 * Types are {@code ToolConfigHyperParamDataType} for hyperparameters and {@code ToolConfigDataType}
 * for inputs/outputs (Learning-APIs).
 */
@TemplateData
@Value
public class FLNetToolField {

    String name;
    String type;
    /** Hyperparameters only, as written into app.yml. */
    String defaultValue;
    String description;
    boolean required;
    boolean hyperparam;

    public static FLNetToolField hyper(String name, String type, String defaultValue, String description) {
        return new FLNetToolField(name, type, defaultValue, description, false, true);
    }

    public static FLNetToolField io(String name, String type, String description, boolean required) {
        return new FLNetToolField(name, type, null, description, required, false);
    }

    /** Python annotation in config.py. */
    public String getPythonType() {
        if (!hyperparam) {
            return "Any";
        }
        return switch (type) {
            case "INTEGER" -> "int";
            case "FLOAT" -> "float";
            case "BOOLEAN" -> "bool";
            default -> "str";
        };
    }

    /** Python default value in config.py. */
    public String getPythonDefault() {
        if (!hyperparam) {
            return "None";
        }
        return switch (type) {
            case "INTEGER", "FLOAT" -> defaultValue;
            case "BOOLEAN" -> Boolean.parseBoolean(defaultValue) ? "True" : "False";
            default -> '"' + defaultValue.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        };
    }
}

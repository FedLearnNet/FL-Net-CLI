package bio.cosy.flnet.cli.base.tool;

import io.quarkus.qute.TemplateData;
import lombok.Value;

@TemplateData
@Value
public class FLNetToolField {

    String name;
    String type;
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

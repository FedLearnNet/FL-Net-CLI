package bio.cosy.flnet.cli.base;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Tool types of the FL-Net registry ({@code FederatedAppType} in the Learning-APIs) with the
 * pyfedappwrap base class each one is generated for. The example fields are used consistently in
 * {@code app.yml}, {@code config.py} and {@code app.py}, so a generated project runs as is.
 */
public enum ToolType {
    ANALYSIS("app.py",
            List.of(FLNetToolField.hyper("epochs", "INTEGER", "10", "Number of training epochs.")),
            List.of(FLNetToolField.io("input", "CSV", "Training data.", true)),
            List.of(FLNetToolField.io("output", "CSV", "Per column mean of the training data.", false))),
    PRE_PROCESSING("preprocess_app.py",
            List.of(FLNetToolField.hyper("drop_duplicates", "BOOLEAN", "true", "Remove duplicated rows.")),
            List.of(FLNetToolField.io("input", "CSV", "Data to clean.", true)),
            List.of(FLNetToolField.io("output", "CSV", "Cleaned data.", false))),
    POST_PROCESSING("preprocess_app.py",
            List.of(FLNetToolField.hyper("drop_duplicates", "BOOLEAN", "true", "Remove duplicated rows.")),
            List.of(FLNetToolField.io("input", "CSV", "Data to post-process.", true)),
            List.of(FLNetToolField.io("output", "CSV", "Post-processed data.", false))),
    EVALUATION("evaluation_app.py",
            List.of(FLNetToolField.hyper("report_name", "STRING", "evaluation.txt", "File name of the written report.")),
            List.of(FLNetToolField.io("input", "CSV", "Data to evaluate.", true)),
            List.of(FLNetToolField.io("report", "TEXT", "Evaluation report.", false))),
    SELF_LEARNED("self_learned_app.py",
            List.of(FLNetToolField.hyper("iterations", "INTEGER", "10", "Number of algorithm iterations.")),
            List.of(FLNetToolField.io("input", "CSV", "Data the algorithm runs on.", true)),
            List.of(FLNetToolField.io("report", "TEXT", "Algorithm result.", false))),
    DATA_TRANSFORMATION("transformer_app.py",
            List.of(),
            List.of(FLNetToolField.io("input", "CSV", "Values to transform.", true)),
            List.of(FLNetToolField.io("output", "CSV", "Transformed values.", false))),
    EXTRACTOR("extractor_app.py",
            List.of(FLNetToolField.hyper("rows", "INTEGER", "3", "Number of rows to extract.")),
            List.of(),
            List.of(FLNetToolField.io("output", "CSV", "Extracted data.", false))),
    EXPORT("export_app.py",
            List.of(FLNetToolField.hyper("report_name", "STRING", "export_report.txt", "File name of the written report.")),
            List.of(FLNetToolField.io("input", "CSV", "Data to export.", true)),
            List.of(FLNetToolField.io("report", "TEXT", "Export summary.", false)));

    private final String appTemplate;
    private final List<FLNetToolField> hyperparams;
    private final List<FLNetToolField> inputs;
    private final List<FLNetToolField> outputs;

    ToolType(String appTemplate, List<FLNetToolField> hyperparams, List<FLNetToolField> inputs, List<FLNetToolField> outputs) {
        this.appTemplate = appTemplate;
        this.hyperparams = hyperparams;
        this.inputs = inputs;
        this.outputs = outputs;
    }

    /** CLI spelling, e.g. {@code pre-processing}. */
    public String cliName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static ToolType fromCli(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return Arrays.stream(values()).filter(t -> t.name().equals(normalized)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool type '" + value + "'. Choose one of: " + cliNames() + "."));
    }

    public static String cliNames() {
        return Arrays.stream(values()).map(ToolType::cliName).collect(Collectors.joining(", "));
    }

    public String appTemplate() {
        return appTemplate;
    }

    /** Transformers are configured through {@code BaseTransformerConfig} inside app.py instead. */
    public boolean hasConfigModule() {
        return this != DATA_TRANSFORMATION;
    }

    /** Only generic analyses can run as federated tools (client app + aggregator). */
    public boolean supportsFederated() {
        return this == ANALYSIS;
    }

    public List<FLNetToolField> hyperparams(boolean federated) {
        if (federated) {
            return List.of(FLNetToolField.hyper("communication_id", "STRING", "round-1",
                    "Communication id shared between clients and aggregator for one federated round."));
        }
        return hyperparams;
    }

    public List<FLNetToolField> inputs() {
        return inputs;
    }

    public List<FLNetToolField> outputs(boolean federated) {
        return federated ? List.of(FLNetToolField.io("output", "CSV", "Federated mean of the numeric input columns.", false)) : outputs;
    }
}

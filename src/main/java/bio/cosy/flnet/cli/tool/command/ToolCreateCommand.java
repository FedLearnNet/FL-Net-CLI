package bio.cosy.flnet.cli.tool.command;

import bio.cosy.flnet.cli.base.tool.FLNetTool;
import bio.cosy.flnet.cli.base.tool.ToolType;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.helper.InteractionOptions;
import bio.cosy.flnet.cli.helper.Prompter;
import bio.cosy.flnet.cli.helper.Prompter.Validator;
import bio.cosy.flnet.cli.helper.WebAddress;
import bio.cosy.flnet.cli.tool.bo.FLNetToolBO;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "create",
        description = "Create a new FL-Net tool project (Python, pyfedappwrap) from a template.",
        footer = {
                "",
                "Tool types: " + "analysis, pre-processing, post-processing, evaluation, self-learned, data-transformation, extractor, export",
                "",
                "Examples:",
                "  flnet tool create \"Random Forest\"",
                "  flnet tool create my-cleaner --type pre-processing --no-input",
                "  flnet tool create fed-mean --type analysis --federated --app-id 42"
        })
public class ToolCreateCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<name>", description = "Name of the tool, e.g. \"Random Forest\".")
    String name;

    @Option(names = {"-t", "--type"}, paramLabel = "<type>", description = "Tool type (see below). Default: analysis.")
    String type;

    @Option(names = "--federated", negatable = true, description = "Generate a federated tool (client app + aggregator). Analysis tools only.")
    Boolean federated;

    @Option(names = {"-o", "--output"}, paramLabel = "<dir>", description = "Target directory. Default: ./<slug>.")
    Path output;

    @Option(names = "--description", paramLabel = "<text>", description = "Short description for app.yml.")
    String description;

    @Option(names = "--source-url", paramLabel = "<url>", description = "Repository URL for app.yml.")
    String sourceUrl;

    @Option(names = "--app-id", paramLabel = "<id>",
            description = "Id of the tool on the platform (tool detail page). Default: ${bundle:flnet.tool.app-id}.")
    String appId;

    @Option(names = "--platform", paramLabel = "<url>",
            description = "Platform the tool is developed against (.env URLs). Default: ${bundle:flnet.tool.platform-url}.")
    String platform;

    @Option(names = "--config-sync", negatable = true,
            description = "Sync app.yml with the platform on startup. Default: ${bundle:flnet.tool.env.config-sync}.")
    Boolean configSync;

    @Option(names = "--trace-performance", negatable = true,
            description = "Report CPU/memory usage. Default: ${bundle:flnet.tool.env.trace-performance}.")
    Boolean tracePerformance;

    @Option(names = "--project-startup", negatable = true,
            description = "Set up the project on the platform at startup. Default: ${bundle:flnet.tool.env.project-startup}.")
    Boolean projectStartup;

    @Option(names = "--prio-local-config", negatable = true,
            description = "Prefer the local app.yml over the platform configuration. Default: ${bundle:flnet.tool.env.prio-local-config}.")
    Boolean prioLocalConfig;

    @Option(names = "--send-console-logs", negatable = true,
            description = "Forward console output to the platform. Default: ${bundle:flnet.tool.env.send-console-logs}.")
    Boolean sendConsoleLogs;

    @Option(names = "--sdk-version", paramLabel = "<version>",
            description = "FL-Net-Python-Tool-API version for requirements.txt. Default: ${bundle:flnet.tool.sdk-version}.")
    String sdkVersion;

    @Option(names = "--base-image", paramLabel = "<image>",
            description = "Base image of the Dockerfile. Default: ${bundle:flnet.tool.base-image}.")
    String baseImage;

    @Option(names = "--force", description = "Write into an existing, non-empty directory (overwrites generated files).")
    boolean force;

    @Mixin
    InteractionOptions interaction;

    @Inject
    Prompter prompter;

    @Inject
    FLNetToolBO toolBO;

    @Override
    public Integer call() {
        prompter.configure(interaction);

        FLNetTool tool = toolBO.create(prompter.text("<name>", name, "Tool name", null, Validator.NOT_EMPTY));
        try {
            tool.setToolType(type != null ? ToolType.fromCli(type)
                    : prompter.choice("--type", null, "Tool type", List.of(ToolType.values()), ToolType::cliName, ToolType.ANALYSIS));
        } catch (IllegalArgumentException e) {
            throw CliException.usage(e.getMessage());
        }
        if (Boolean.TRUE.equals(federated) && !tool.getToolType().supportsFederated()) {
            throw CliException.usage("--federated is only supported for analysis tools.");
        }
        tool.setFederated(tool.getToolType().supportsFederated()
                && prompter.confirm("--federated", federated, "Federated learning (client app + aggregator)?", false));
        tool.setDescription(prompter.text("--description", description, "Short description",
                "An FL-Net " + tool.getToolType().cliName() + " tool", Validator.ANY));
        tool.setSourceUrl(sourceUrl);
        if (platform != null) {
            try {
                tool.setPlatformAddress(WebAddress.parse(platform));
            } catch (IllegalArgumentException e) {
                throw CliException.usage("Invalid value for --platform: " + e.getMessage());
            }
        }
        if (appId != null) {
            tool.setAppId(appId);
        }
        setIfGiven(configSync, tool::setConfigSync);
        setIfGiven(tracePerformance, tool::setTracePerformance);
        setIfGiven(projectStartup, tool::setProjectStartup);
        setIfGiven(prioLocalConfig, tool::setPrioLocalConfig);
        setIfGiven(sendConsoleLogs, tool::setSendConsoleLogs);
        setIfGiven(sdkVersion, tool::setSdkVersion);
        setIfGiven(baseImage, tool::setBaseImage);
        tool.setDirectory(output != null ? output : Path.of(tool.getSlug()));

        toolBO.generate(tool, force);

        ConsoleHelper.success("Created " + tool.getToolType().cliName() + (tool.isFederated() ? " (federated)" : "") + " tool '"
                + tool.getName() + "' in " + tool.getDirectory());
        ConsoleHelper.info("Next steps:");
        Path relative = Path.of("").toAbsolutePath().relativize(tool.getDirectory());
        ConsoleHelper.command("cd " + (relative.toString().isEmpty() ? "." : relative));
        ConsoleHelper.command("python3 -m venv .venv && source .venv/bin/activate");
        ConsoleHelper.command("pip install -r requirements.txt");
        if (toolBO.needsAppId(tool)) {
            ConsoleHelper.info("Set APP_ID in .env to the id of your tool on the platform, then run:");
        }
        ConsoleHelper.command("python main.py");
        return 0;
    }

    private static <T> void setIfGiven(T value, java.util.function.Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}

package bio.cosy.flnet.cli.tool;

import bio.cosy.flnet.cli.config.FLNetCliConfig;
import bio.cosy.flnet.cli.base.FLNetTool;
import bio.cosy.flnet.cli.support.CliException;
import bio.cosy.flnet.cli.support.WebAddress;
import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Validator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Generates tool projects: offline counterpart of the platform's tool startup generator
 * ({@code ToolStartupGeneratorBO} in global-learning-api), rendering the Qute templates in
 * {@code templates/tool} with the {@link FLNetTool} as {@code tool}.
 */
@ApplicationScoped
public class FLNetToolBO {

    @Inject
    FLNetCliConfig config;

    @Inject
    Engine qute;

    @Inject
    Validator validator;

    /** A new tool with the configured defaults ({@code flnet.tool.*}). */
    public FLNetTool create(String name) {
        FLNetCliConfig.ToolConfig defaults = config.tool();
        FLNetTool tool = new FLNetTool();
        tool.setName(name);
        tool.setAppId(defaults.appId());
        tool.setPlatformAddress(WebAddress.parse(defaults.platformUrl()));
        tool.setKeycloakRealmPath(config.keycloakRealmPath());
        tool.setConfigSync(defaults.env().configSync());
        tool.setTracePerformance(defaults.env().tracePerformance());
        tool.setProjectStartup(defaults.env().projectStartup());
        tool.setPrioLocalConfig(defaults.env().prioLocalConfig());
        tool.setSendConsoleLogs(defaults.env().sendConsoleLogs());
        tool.setSdkVersion(defaults.sdkVersion());
        tool.setBaseImage(defaults.baseImage());
        return tool;
    }

    /** The app id is still the configured placeholder, so APP_ID must be set before running. */
    public boolean needsAppId(FLNetTool tool) {
        return !tool.isLinkedToPlatform(config.tool().appId());
    }

    /**
     * Writes the project into the tool's directory.
     *
     * @param force write into a non-empty directory (overwrites generated files)
     */
    public void generate(FLNetTool tool, boolean force) {
        tool.requireValid(validator);
        checkTarget(tool.getDirectory(), force);
        render(tool, "README.md", "README.md");
        render(tool, "main.py", "main.py");
        render(tool, tool.getToolType().appTemplate(), "app.py");
        if (tool.hasConfigModule()) {
            render(tool, "config.py", "config.py");
        }
        if (tool.isFederated()) {
            render(tool, "aggregator.py", "aggregator.py");
        }
        render(tool, "app.yml", "app.yml");
        render(tool, "env", ".env");
        render(tool, "requirements.txt", "requirements.txt");
        render(tool, "Dockerfile", "Dockerfile");
        render(tool, "gitignore", ".gitignore");
        if (tool.hasInputs()) {
            render(tool, "input.csv", "data/input.csv");
        } else {
            write(tool.resolve("data/.gitkeep"), "");
        }
    }

    private static void checkTarget(Path target, boolean force) {
        if (!Files.exists(target)) {
            return;
        }
        if (!Files.isDirectory(target)) {
            throw CliException.usage(target + " exists and is not a directory.");
        }
        try (Stream<Path> entries = Files.list(target)) {
            if (entries.findAny().isPresent() && !force) {
                throw CliException.usage(target + " is not empty. Choose another --output or pass --force to overwrite generated files.");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void render(FLNetTool tool, String template, String file) {
        Template t = qute.getTemplate("tool/" + template);
        if (t == null) {
            throw new IllegalStateException("Template tool/" + template + " is missing, the CLI was built incorrectly.");
        }
        write(tool.resolve(file), t.data("tool", tool).render());
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }
}

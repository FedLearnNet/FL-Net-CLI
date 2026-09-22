package bio.cosy.flnet.cli.tool.bo;

import bio.cosy.flnet.cli.base.tool.FLNetTool;
import bio.cosy.flnet.cli.config.FLNetCliConfig;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.WebAddress;
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

@ApplicationScoped
public class FLNetToolBO {

    @Inject
    FLNetCliConfig config;

    @Inject
    Engine qute;

    @Inject
    Validator validator;

    public FLNetTool create(String name) {
        FLNetCliConfig.ToolSettings defaults = config.tool();
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

    public boolean needsAppId(FLNetTool tool) {
        return !tool.isLinkedToPlatform(config.tool().appId());
    }

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

package bio.cosy.flnet.cli.deploy.command;

import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.deploy.bo.BaseFLNetDeploymentBO;
import bio.cosy.flnet.cli.deploy.bo.ComposeBO;
import bio.cosy.flnet.cli.deploy.config.BaseDeploymentConfig;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.helper.InteractionOptions;
import bio.cosy.flnet.cli.helper.Prompter;
import jakarta.inject.Inject;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

public abstract class BaseInitCommand implements Callable<Integer> {

    @Mixin
    protected InstanceOptions instanceOptions;

    @Mixin
    protected InteractionOptions interaction;

    @Option(names = "--compose", paramLabel = "<file>", arity = "0..1",
            description = "Also generate one standalone docker compose file (secrets inlined), by default <dir>/"
                    + "${bundle:flnet.compose.generated-file}. Same as running the 'compose' command afterwards.")
    protected String compose;

    @Option(names = "--refresh-files",
            description = "Overwrite docker-compose.yml, nginx and keycloak files with the versions shipped in this CLI.")
    protected boolean refreshFiles;

    @Option(names = "--bundle-dir", paramLabel = "<dir>", hidden = true,
            description = "Use deployment files from a local checkout of this deployment's bundle.")
    protected Path bundleDir;

    @Inject
    protected Prompter prompter;

    @Inject
    ComposeBO composeBO;

    protected <T extends BaseFLNetDeployableInstance> T prepare(BaseFLNetDeploymentBO<T> bo, BaseDeploymentConfig given) {
        prompter.configure(interaction);
        ConsoleHelper.heading(bo.kind().displayName() + " setup");
        T instance = bo.prepareForInit(instanceOptions, prompter);
        if (given.getImageTag() != null) {
            instance.setImageTag(given.getImageTag());
        }
        ConsoleHelper.info("Instance '" + instance.getName() + "' in " + instance.getDirectory());
        return instance;
    }

    protected <T extends BaseFLNetDeployableInstance> void write(BaseFLNetDeploymentBO<T> bo, T instance, BaseDeploymentConfig given) {
        if (given.getFrontendImage() != null) {
            instance.setFrontendImage(given.getFrontendImage());
        }
        ConsoleHelper.heading("Writing configuration");
        boolean generated = bo.ensureSecrets(instance);
        ConsoleHelper.success(bo.save(instance, refreshFiles, bundleDir).summary());
        ConsoleHelper.success(generated ? "Secrets: generated in " + instance.getSecretsDirectory() + " (readable by your user only)"
                : "Secrets: kept existing files in " + instance.getSecretsDirectory());
        ConsoleHelper.success("Configuration written to " + instance.getEnvFile());
        if (compose != null) {
            ComposeCommands.printExport(composeBO, instance, compose, false);
        }
    }

    protected static void nextSteps(List<String> lines) {
        ConsoleHelper.heading("Next steps");
        ConsoleHelper.lines(lines);
    }
}

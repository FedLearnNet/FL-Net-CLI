package bio.cosy.flnet.cli.deploy.bo;

import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.base.deployment.DeploymentKind;
import bio.cosy.flnet.cli.config.FLNetCliConfig;
import bio.cosy.flnet.cli.deploy.DeploymentBundleHelper;
import bio.cosy.flnet.cli.deploy.PortPlanner;
import bio.cosy.flnet.cli.deploy.command.InstanceOptions;
import bio.cosy.flnet.cli.migration.bo.FLNetMigrationBO;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.helper.EnvFileHelper;
import bio.cosy.flnet.cli.helper.Prompter;
import jakarta.inject.Inject;
import jakarta.validation.Validator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class BaseFLNetDeploymentBO<T extends BaseFLNetDeployableInstance> {

    @Inject
    protected FLNetCliConfig config;

    @Inject
    FLNetDeploymentsBO deployments;

    @Inject
    Validator validator;

    @Inject
    FLNetMigrationBO migrations;

    public abstract DeploymentKind kind();

    protected abstract T create();

    protected abstract String baseProjectName();

    protected void afterLoad(T instance) {
    }

    // ---------------------------------------------------------------- locations

    public Path home() {
        return config.home().filter(h -> !h.isBlank())
                .map(h -> Path.of(h).toAbsolutePath().normalize())
                .orElse(Path.of(System.getProperty("user.home"), "fl-net"));
    }

    public Path instancesDir() {
        return home().resolve(kind().folder());
    }

    public String projectName(String instanceName) {
        return BaseFLNetDeployableInstance.DEFAULT_NAME.equals(instanceName) ? baseProjectName() : baseProjectName() + "-" + instanceName;
    }

    // ---------------------------------------------------------------- lookup

    public T newInstance(String name, Path directory) {
        T instance = create();
        instance.setName(name);
        instance.setDirectory(directory);
        instance.setProjectName(projectName(name));
        return instance;
    }

    public T load(Path directory, String fallbackName) {
        T instance = newInstance(fallbackName, directory);
        instance.load();
        afterLoad(instance);
        return instance;
    }

    public List<T> list() {
        Path root = instancesDir();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(root)) {
            return entries
                    .filter(entry -> Files.isRegularFile(entry.resolve(".env")))
                    .map(entry -> {
                        T instance = load(realPath(entry), entry.getFileName().toString());
                        // the link name is what users type, even if .env says otherwise
                        instance.setName(entry.getFileName().toString());
                        return instance;
                    })
                    .sorted(Comparator.comparing(T::getName))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + root, e);
        }
    }

    public Optional<T> find(String name) {
        return list().stream().filter(i -> i.getName().equals(name)).findFirst();
    }

    public T select(InstanceOptions options, Prompter prompter) {
        if (options.dir != null) {
            Path dir = options.dir.toAbsolutePath().normalize();
            T instance = load(dir, options.name != null ? options.name : dir.getFileName().toString());
            instance.requireInitialized();
            return instance;
        }
        List<T> all = list();
        if (options.name != null) {
            return all.stream().filter(i -> i.getName().equals(options.name)).findFirst()
                    .orElseThrow(() -> CliException.usage("No " + kind().displayName() + " named '" + options.name + "'. "
                            + (all.isEmpty() ? "None is set up yet: run 'flnet " + kind().bundle() + " init'." : "Existing: " + names(all) + ".")));
        }
        if (all.isEmpty()) {
            throw new CliException("No " + kind().displayName() + " is set up on this machine (" + instancesDir()
                    + "). Run 'flnet " + kind().bundle() + " init' first.", CliException.ENVIRONMENT);
        }
        if (all.size() == 1) {
            return all.getFirst();
        }
        if (prompter != null && prompter.isInteractive()) {
            return prompter.choice("--name", null, "Which " + kind().bundle() + "?", all, T::getName, all.getFirst());
        }
        throw CliException.usage(all.size() + " " + kind().displayName() + " instances exist (" + names(all)
                + "). Choose one with --name.");
    }

    public T prepareForInit(InstanceOptions options, Prompter prompter) {
        List<T> all = list();
        String name = options.name;
        if (name == null && options.dir != null) {
            // an existing deployment outside the instances home keeps its name
            name = EnvFileHelper.read(options.dir.resolve(".env")).get(BaseFLNetDeployableInstance.NAME_VARIABLE);
        }
        if (name == null) {
            name = askName(all, prompter);
        }
        String error = BaseFLNetDeployableInstance.validateName(name);
        if (error != null) {
            throw CliException.usage("Invalid value for --name: " + error);
        }
        String finalName = name;
        Optional<T> existing = all.stream().filter(i -> i.getName().equals(finalName)).findFirst();
        Path dir;
        if (options.dir != null) {
            dir = options.dir.toAbsolutePath().normalize();
            if (existing.isPresent() && !existing.get().getDirectory().equals(realPath(dir))) {
                throw CliException.usage("The name '" + name + "' is already used by " + existing.get().getDirectory() + ".");
            }
        } else {
            dir = existing.map(T::getDirectory).orElse(instancesDir().resolve(name));
        }
        if (existing.isEmpty() && !all.isEmpty()) {
            ConsoleHelper.info("Setting up an additional " + kind().displayName() + " named '" + name + "'.");
        }
        if (Files.isRegularFile(dir.resolve(".env"))) {
            T instance = load(dir, name);
            instance.setName(name);
            return instance;
        }
        return newInstance(name, dir);
    }

    private String askName(List<T> all, Prompter prompter) {
        if (all.isEmpty()) {
            return prompter.text("--name", null, "Name of this " + kind().bundle() + " (several can run on one server)",
                    BaseFLNetDeployableInstance.DEFAULT_NAME, BaseFLNetDeployableInstance::validateName);
        }
        ConsoleHelper.info("This machine already has " + all.size() + " " + kind().displayName() + " instance" + (all.size() == 1 ? "" : "s") + ":");
        all.forEach(i -> ConsoleHelper.info("  " + summary(i)));
        if (!prompter.isInteractive()) {
            throw CliException.usage("Pass --name: an existing name (" + names(all) + ") reconfigures that "
                    + kind().bundle() + ", a new name sets up another one.");
        }
        ConsoleHelper.info("Enter an existing name to reconfigure it, or a new name to set up another " + kind().bundle() + ".");
        return prompter.text("--name", null, "Name", null, BaseFLNetDeployableInstance::validateName);
    }

    // ---------------------------------------------------------------- ports

    public PortPlanner portPlanner(T instance) {
        List<BaseFLNetDeployableInstance> others = deployments.listAll().stream()
                .filter(other -> !(other.getKind() == instance.getKind() && other.getName().equals(instance.getName())))
                .toList();
        return new PortPlanner(instance, others);
    }

    // ---------------------------------------------------------------- saving

    public DeploymentBundleHelper.InstallResult save(T instance, boolean refreshFiles, Path bundleDir) {
        migrations.requireCompatibleForInit(kind(), instance.getDirectory());
        boolean fresh = !Files.exists(instance.getEnvFile());
        instance.requireValid(validator);
        DeploymentBundleHelper.InstallResult files = DeploymentBundleHelper.install(kind(), instance.getDirectory(), bundleDir, refreshFiles);
        if (!BaseFLNetDeployableInstance.DEFAULT_NAME.equals(instance.getName())) {
            DeploymentBundleHelper.requireScopedContainerNames(instance);
        }
        instance.toSecretFiles().forEach((file, variables) -> EnvFileHelper.write(instance.getSecretsDirectory().resolve(file), variables));
        EnvFileHelper.write(instance.getEnvFile(), instance.toEnv(), instance.envComments());
        afterSave(instance);
        if (fresh && bundleDir == null) {
            migrations.recordFreshDeployment(kind(), instance.getDirectory());
        }
        register(instance);
        return files;
    }

    protected void afterSave(T instance) {
    }

    public boolean ensureSecrets(T instance) {
        if (instance.hasSecrets()) {
            return false;
        }
        instance.generateSecrets(config.secrets().length(), config.secrets().adminPasswordLength());
        return true;
    }

    public boolean hasStoredSecrets(T instance) {
        return instance.getSecretFileNames().stream().anyMatch(file -> Files.exists(instance.getSecretsDirectory().resolve(file)));
    }

    public void register(T instance) {
        Path link = instancesDir().resolve(instance.getName());
        try {
            if (instance.getDirectory().equals(link) || (Files.exists(link) && realPath(link).equals(realPath(instance.getDirectory())))) {
                return;
            }
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                throw CliException.usage(link + " already exists; choose another --name.");
            }
            Files.createDirectories(link.getParent());
            Files.createSymbolicLink(link, instance.getDirectory());
        } catch (IOException | UnsupportedOperationException e) {
            ConsoleHelper.warn("Could not link " + instance.getDirectory() + " into " + link + " (" + e.getMessage()
                    + "); use --dir to manage this " + kind().bundle() + ".");
        }
    }

    public void delete(BaseFLNetDeployableInstance instance) {
        Path link = instancesDir().resolve(instance.getName());
        try {
            if (Files.isSymbolicLink(link) && realPath(link).equals(realPath(instance.getDirectory()))) {
                Files.delete(link);
            }
            if (Files.isDirectory(instance.getDirectory())) {
                try (Stream<Path> files = Files.walk(instance.getDirectory())) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(file);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete " + instance.getDirectory(), e);
        }
    }

    // ---------------------------------------------------------------- presentation helpers

    public String nameFlag(BaseFLNetDeployableInstance instance) {
        List<T> all = list();
        boolean only = all.size() == 1 && all.getFirst().getName().equals(instance.getName());
        return only ? "" : " --name " + instance.getName();
    }

    public static String summary(BaseFLNetDeployableInstance instance) {
        return String.format("%-12s %-40s ports %s", instance.getName(), instance.getAddress(),
                instance.getPorts().stream().map(String::valueOf).collect(Collectors.joining(", ")));
    }

    private static String names(List<? extends BaseFLNetDeployableInstance> all) {
        return all.stream().map(BaseFLNetDeployableInstance::getName).collect(Collectors.joining(", "));
    }

    static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}

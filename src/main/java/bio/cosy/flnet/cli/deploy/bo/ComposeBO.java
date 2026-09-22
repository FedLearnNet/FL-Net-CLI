package bio.cosy.flnet.cli.deploy.bo;

import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.base.deployment.DeploymentKind;
import bio.cosy.flnet.cli.config.FLNetCliConfig;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.FilePermissionHelper;
import bio.cosy.flnet.cli.helper.ProcessHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@ApplicationScoped
public class ComposeBO {

    @Inject
    FLNetCliConfig config;

    public int up(BaseFLNetDeployableInstance instance, boolean pull) {
        List<Path> missing = instance.getMissingCertificates();
        if (!missing.isEmpty()) {
            String hint = instance.getKind() == DeploymentKind.CLIENT
                    ? " Generate self-signed certificates with 'flnet client certs' or fix the path in .env."
                    : " Fix the path in .env or provide the certificate.";
            throw new CliException("SSL is enabled but " + missing.stream().map(Path::toString).collect(Collectors.joining(", "))
                    + " does not exist." + hint, CliException.ENVIRONMENT);
        }
        if (pull) {
            int code = compose(instance, "pull");
            if (code != 0) {
                return code;
            }
        }
        return compose(instance, "up", "-d");
    }

    public int down(BaseFLNetDeployableInstance instance, boolean volumes) {
        return volumes ? compose(instance, "down", "-v") : compose(instance, "down");
    }

    public int pull(BaseFLNetDeployableInstance instance) {
        return compose(instance, "pull");
    }

    public int stop(BaseFLNetDeployableInstance instance, List<String> services) {
        return compose(instance, withServices(List.of("stop"), services));
    }

    public int restart(BaseFLNetDeployableInstance instance, List<String> services) {
        return compose(instance, withServices(List.of("restart"), services));
    }

    public int clean(BaseFLNetDeployableInstance instance, boolean images) {
        return images ? compose(instance, "down", "--volumes", "--remove-orphans", "--rmi", "all")
                : compose(instance, "down", "--volumes", "--remove-orphans");
    }

    public int status(BaseFLNetDeployableInstance instance) {
        return compose(instance, "ps", "--all");
    }

    public int logs(BaseFLNetDeployableInstance instance, String tail, boolean follow, List<String> services) {
        List<String> args = new ArrayList<>(List.of("logs", "--tail", tail));
        if (follow) {
            args.add("--follow");
        }
        return compose(instance, withServices(args, services));
    }

    public Optional<Path> export(BaseFLNetDeployableInstance instance, String output, boolean keepVariables) {
        instance.requireInitialized();
        List<String> command = new ArrayList<>(List.of("docker", "compose", "config"));
        if (keepVariables) {
            command.add("--no-interpolate");
        }
        if ("-".equals(output)) {
            // stdout carries only the compose file, so it can be piped
            int code = ProcessHelper.runInteractive(instance.getDirectory(), command);
            if (code != 0) {
                throw new CliException("'docker compose config' failed with exit code " + code + ".", code);
            }
            return Optional.empty();
        }
        Path target = output == null || output.isEmpty()
                ? instance.resolve(config.compose().generatedFile()) : Path.of(output).toAbsolutePath().normalize();
        int code = ProcessHelper.runToFile(instance.getDirectory(), command, target);
        if (code != 0) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException _) {
                // the failed command is the more important error
            }
            throw new CliException("'docker compose config' failed with exit code " + code + ". Is docker compose installed? "
                    + "Run 'flnet doctor'.", CliException.ENVIRONMENT);
        }
        if (keepVariables) {
            FilePermissionHelper.set(target, FilePermissionHelper.READABLE);
        }
        return Optional.of(target);
    }

    public boolean isRunning(BaseFLNetDeployableInstance instance) {
        return containerStates(instance).map(all -> all.contains("running")).orElse(false);
    }

    public String containerState(BaseFLNetDeployableInstance instance) {
        Optional<List<String>> states = containerStates(instance);
        if (states.isEmpty()) {
            return "unknown (docker not reachable)";
        }
        List<String> all = states.get();
        if (all.isEmpty()) {
            return "not started";
        }
        long running = all.stream().filter("running"::equals).count();
        return running == 0 ? "stopped" : "running " + running + "/" + all.size();
    }

    private Optional<List<String>> containerStates(BaseFLNetDeployableInstance instance) {
        Optional<String> states = ProcessHelper.output("docker", "ps", "--all",
                "--filter", "label=com.docker.compose.project=" + instance.getProjectName(), "--format", "{{.State}}");
        return states.map(out -> out.lines().map(String::strip).filter(s -> !s.isEmpty()).toList());
    }

    private static String[] withServices(List<String> args, List<String> services) {
        List<String> all = new ArrayList<>(args);
        all.addAll(services);
        return all.toArray(String[]::new);
    }

    private int compose(BaseFLNetDeployableInstance instance, String... args) {
        instance.requireInitialized();
        List<String> command = new ArrayList<>(List.of("docker", "compose"));
        command.addAll(List.of(args));
        return ProcessHelper.runInteractive(instance.getDirectory(), command);
    }

}

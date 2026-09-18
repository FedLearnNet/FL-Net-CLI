package bio.cosy.flnet.cli.deploy;

import bio.cosy.flnet.cli.config.FLNetCliConfig;
import bio.cosy.flnet.cli.base.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.base.DeploymentKind;
import bio.cosy.flnet.cli.support.CliException;
import bio.cosy.flnet.cli.support.FilePermissions;
import bio.cosy.flnet.cli.support.Processes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Runs docker compose for a deployment and reports its container state. */
@ApplicationScoped
public class ComposeBO {

    @Inject
    FLNetCliConfig config;

    /** Starts the deployment, refusing early when SSL certificates are missing. */
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

    /** Stops the containers but keeps them, so {@code up} or {@code restart} continues quickly. */
    public int stop(BaseFLNetDeployableInstance instance, List<String> services) {
        return compose(instance, withServices(List.of("stop"), services));
    }

    /** Restarts the containers as they are; changes of .env or images need {@link #up} instead. */
    public int restart(BaseFLNetDeployableInstance instance, List<String> services) {
        return compose(instance, withServices(List.of("restart"), services));
    }

    /**
     * Removes containers, networks and volumes (all data) of this deployment, with {@code images}
     * also the images of its services (shared with other instances of the same kind).
     */
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

    /**
     * Generates one standalone compose file with {@code docker compose config}.
     *
     * @param output target file, {@code -} for stdout, or {@code null}/empty for
     *               {@code flnet.compose.generated-file} in the deployment directory
     * @param keepVariables keep {@code ${VAR}} references and env_file entries instead of inlining
     *                      values, so the result contains no secrets
     * @return the written file, or empty when printed to stdout
     */
    public Optional<Path> export(BaseFLNetDeployableInstance instance, String output, boolean keepVariables) {
        instance.requireInitialized();
        List<String> command = new ArrayList<>(List.of("docker", "compose", "config"));
        if (keepVariables) {
            command.add("--no-interpolate");
        }
        if ("-".equals(output)) {
            // stdout carries only the compose file, so it can be piped
            int code = Processes.runInteractive(instance.getDirectory(), command);
            if (code != 0) {
                throw new CliException("'docker compose config' failed with exit code " + code + ".", code);
            }
            return Optional.empty();
        }
        Path target = output == null || output.isEmpty()
                ? instance.resolve(config.compose().generatedFile()) : Path.of(output).toAbsolutePath().normalize();
        int code = Processes.runToFile(instance.getDirectory(), command, target);
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
            FilePermissions.set(target, FilePermissions.READABLE);
        }
        return Optional.of(target);
    }

    /** Whether at least one container of the deployment runs. */
    public boolean isRunning(BaseFLNetDeployableInstance instance) {
        return containerStates(instance).map(all -> all.contains("running")).orElse(false);
    }

    /** "running 12/13", "stopped", "not started" or "unknown" (docker not reachable). */
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

    /** State of every container of the deployment, empty when docker is not reachable. */
    private Optional<List<String>> containerStates(BaseFLNetDeployableInstance instance) {
        Optional<String> states = Processes.output("docker", "ps", "--all",
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
        return Processes.runInteractive(instance.getDirectory(), command);
    }

}

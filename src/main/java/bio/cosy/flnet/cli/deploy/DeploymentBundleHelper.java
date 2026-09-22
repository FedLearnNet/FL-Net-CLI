package bio.cosy.flnet.cli.deploy;

import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.base.deployment.DeploymentKind;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.FilePermissionHelper;
import lombok.Value;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class DeploymentBundleHelper {

    @Value
    public static class InstallResult {
        List<String> written;
        List<String> kept;

        public String summary() {
            return "Deployment files: " + written.size() + " written, " + kept.size() + " kept"
                    + (kept.isEmpty() ? "" : " (use --refresh-files to overwrite)");
        }
    }

    private DeploymentBundleHelper() {
    }

    public static InstallResult install(DeploymentKind kind, Path target, Path bundleDir, boolean overwrite) {
        List<String> written = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        try {
            Files.createDirectories(target);
            for (String file : files(kind, bundleDir)) {
                Path destination = target.resolve(file).normalize();
                if (!destination.startsWith(target)) {
                    throw new IllegalStateException("Bundle entry escapes target directory: " + file);
                }
                if (Files.exists(destination) && !overwrite) {
                    kept.add(file);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                try (InputStream in = open(kind, bundleDir, file)) {
                    Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                if (file.endsWith(".sh")) {
                    FilePermissionHelper.set(destination, FilePermissionHelper.EXECUTABLE);
                }
                written.add(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write deployment files to " + target, e);
        }
        return new InstallResult(written, kept);
    }

    public static void requireScopedContainerNames(BaseFLNetDeployableInstance instance) {
        Path compose = instance.getComposeFile();
        try {
            List<String> fixed = Files.readAllLines(compose, StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(line -> line.startsWith("container_name:") && !line.contains("${COMPOSE_PROJECT_NAME}"))
                    .toList();
            if (!fixed.isEmpty()) {
                throw new CliException(compose + " uses fixed container names (" + String.join(", ", fixed)
                        + "), so only one instance can run per machine. Re-run init with --refresh-files to get "
                        + "deployment files that support named instances.", CliException.ENVIRONMENT);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + compose, e);
        }
    }

    static List<String> files(DeploymentKind kind, Path bundleDir) throws IOException {
        if (bundleDir != null) {
            if (!Files.isRegularFile(bundleDir.resolve("docker-compose.yml"))) {
                throw CliException.usage("--bundle-dir '" + bundleDir + "' does not contain a docker-compose.yml.");
            }
            try (Stream<Path> walk = Files.walk(bundleDir)) {
                return walk.filter(Files::isRegularFile)
                        .map(p -> bundleDir.relativize(p).toString().replace('\\', '/'))
                        .filter(DeploymentBundleHelper::isDeploymentFile)
                        .sorted()
                        .toList();
            }
        }
        String manifest = "/bundles/" + kind.bundle() + ".manifest";
        try (InputStream in = DeploymentBundleHelper.class.getResourceAsStream(manifest)) {
            if (in == null) {
                throw new IllegalStateException("Embedded bundle manifest " + manifest + " missing, the CLI was built incorrectly.");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .toList();
        }
    }

    static boolean isDeploymentFile(String relative) {
        String name = relative.substring(relative.lastIndexOf('/') + 1);
        boolean dataDir = (relative.startsWith("umls/") || relative.startsWith("sapbert/")) && !name.equals(".gitignore");
        return !relative.equals(".env") && !relative.startsWith("env/") && !relative.startsWith(".flnet/") && !dataDir
                && !name.endsWith(".pem") && !name.endsWith(".key") && !name.equals("san.cnf")
                && !name.equals(".DS_Store");
    }

    private static InputStream open(DeploymentKind kind, Path bundleDir, String file) throws IOException {
        if (bundleDir != null) {
            return Files.newInputStream(bundleDir.resolve(file));
        }
        InputStream in = DeploymentBundleHelper.class.getResourceAsStream("/bundles/" + kind.bundle() + "/" + file);
        if (in == null) {
            throw new IllegalStateException("Embedded bundle file missing: " + file);
        }
        return in;
    }
}

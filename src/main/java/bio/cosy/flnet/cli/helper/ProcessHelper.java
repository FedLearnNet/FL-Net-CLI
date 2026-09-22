package bio.cosy.flnet.cli.helper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class ProcessHelper {

    private ProcessHelper() {
    }

    public static int runInteractive(Path workingDir, List<String> command) {
        return run(workingDir, command, null);
    }

    public static int runToFile(Path workingDir, List<String> command, Path output) {
        return run(workingDir, command, output);
    }

    private static int run(Path workingDir, List<String> command, Path output) {
        try {
            ProcessBuilder process = new ProcessBuilder(command)
                    .directory(workingDir == null ? null : workingDir.toFile());
            if (output == null) {
                process.inheritIO();
            } else {
                Files.deleteIfExists(output);
                Files.createFile(output);
                FilePermissionHelper.set(output, FilePermissionHelper.OWNER_ONLY);
                process.redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT);
            }
            return process.start().waitFor();
        } catch (IOException e) {
            throw new CliException("Could not run '" + command.get(0) + "': " + e.getMessage()
                    + ". Run 'flnet doctor' to check your setup.", CliException.ENVIRONMENT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CliException.aborted();
        }
    }

    public static Optional<String> probe(String... command) {
        return output(command).map(out -> out.lines().findFirst().orElse("").strip());
    }

    public static Optional<String> output(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            // wait first so a hanging program cannot block us; the outputs read here are a few lines
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.exitValue() == 0 ? Optional.of(output) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}

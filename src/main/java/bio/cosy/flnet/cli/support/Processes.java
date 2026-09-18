package bio.cosy.flnet.cli.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Runs external programs (docker, openssl). */
public final class Processes {

    private Processes() {
    }

    /** Runs with the terminal attached (output streams straight to the user) and returns the exit code. */
    public static int runInteractive(Path workingDir, List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(workingDir == null ? null : workingDir.toFile())
                    .inheritIO()
                    .start();
            return process.waitFor();
        } catch (IOException e) {
            throw new CliException("Could not run '" + command.get(0) + "': " + e.getMessage()
                    + ". Run 'flnet doctor' to check your setup.", CliException.ENVIRONMENT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CliException.aborted();
        }
    }

    /**
     * Runs with stdout written to {@code output} (created with owner-only permissions before the
     * first byte is written) and stderr shown to the user. Returns the exit code.
     */
    public static int runToFile(Path workingDir, List<String> command, Path output) {
        try {
            Files.deleteIfExists(output);
            Files.createFile(output);
            FilePermissions.set(output, FilePermissions.OWNER_ONLY);
            Process process = new ProcessBuilder(command)
                    .directory(workingDir.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.to(output.toFile()))
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            return process.waitFor();
        } catch (IOException e) {
            throw new CliException("Could not run '" + command.get(0) + "': " + e.getMessage()
                    + ". Run 'flnet doctor' to check your setup.", CliException.ENVIRONMENT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CliException.aborted();
        }
    }

    /**
     * Runs quietly and returns the first output line on success, empty when the program is
     * missing, fails, or takes longer than 20 seconds.
     */
    public static Optional<String> probe(String... command) {
        return output(command).map(out -> out.lines().findFirst().orElse("").strip());
    }

    /** Like {@link #probe} but returns the complete output. */
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

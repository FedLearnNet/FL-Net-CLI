package bio.cosy.flnet.cli.helper;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
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
        return waitFor(command, builder(workingDir, command).inheritIO());
    }

    public static ProcessResult runToFile(Path workingDir, List<String> command, Path output) {
        try {
            Files.deleteIfExists(output);
            Files.createFile(output);
        } catch (IOException e) {
            throw failure(command, e);
        }
        FilePermissionHelper.set(output, FilePermissionHelper.OWNER_ONLY);
        return runCapturingErrors(command, builder(workingDir, command).redirectOutput(output.toFile()));
    }

    /**
     * A file as stdin, e.g. a dump into psql. Only errors reach the console.
     */
    public static ProcessResult runFromFile(Path workingDir, List<String> command, Path input) {
        return runCapturingErrors(command, builder(workingDir, command).redirectInput(input.toFile())
                .redirectOutput(Redirect.DISCARD));
    }

    /**
     * Stdout of a command that is parsed, e.g. JSON; stderr goes to the console so warnings cannot break it.
     */
    public static String capture(Path workingDir, List<String> command) {
        try {
            Process process = builder(workingDir, command).redirectError(Redirect.INHERIT).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.waitFor();
            if (code != 0) {
                throw new CliException("'" + String.join(" ", command) + "' failed with exit code " + code + ".", code);
            }
            return output;
        } catch (IOException | InterruptedException e) {
            throw failure(command, e);
        }
    }

    private static ProcessBuilder builder(Path workingDir, List<String> command) {
        return new ProcessBuilder(command).directory(workingDir == null ? null : workingDir.toFile());
    }

    // stderr is read and then shown on the console, so a failure can name its reason
    private static ProcessResult runCapturingErrors(List<String> command, ProcessBuilder builder) {
        try {
            Process process = builder.redirectError(Redirect.PIPE).start();
            String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            System.err.print(error);
            return new ProcessResult(process.waitFor(), error);
        } catch (IOException | InterruptedException e) {
            throw failure(command, e);
        }
    }

    private static int waitFor(List<String> command, ProcessBuilder builder) {
        try {
            return builder.start().waitFor();
        } catch (IOException | InterruptedException e) {
            throw failure(command, e);
        }
    }

    private static CliException failure(List<String> command, Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return CliException.aborted();
        }
        return new CliException("Could not run '" + command.get(0) + "': " + e.getMessage()
                + ". Run 'flnet doctor' to check your setup.", CliException.ENVIRONMENT);
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

package bio.cosy.flnet.cli.support;

/**
 * An expected, user facing failure. Its message is printed without a stack trace and the
 * process exits with {@link #exitCode()}.
 */
public class CliException extends RuntimeException {

    /** Invalid or missing user input, same meaning as picocli's usage error code. */
    public static final int USAGE = 2;
    /** The environment is not ready (missing docker, openssl, uninitialized directory, ...). */
    public static final int ENVIRONMENT = 3;
    /** The user aborted at a confirmation. */
    public static final int ABORTED = 130;

    private final int exitCode;

    public CliException(String message) {
        this(message, 1);
    }

    public CliException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    public static CliException usage(String message) {
        return new CliException(message, USAGE);
    }

    public static CliException aborted() {
        return new CliException("Aborted.", ABORTED);
    }

    public int exitCode() {
        return exitCode;
    }
}

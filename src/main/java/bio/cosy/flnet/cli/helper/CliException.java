package bio.cosy.flnet.cli.helper;

public class CliException extends RuntimeException {

    public static final int USAGE = 2;
    public static final int ENVIRONMENT = 3;
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

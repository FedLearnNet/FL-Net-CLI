package bio.cosy.flnet.cli.support;

import picocli.CommandLine.Help.Ansi;

import java.util.List;

/**
 * Terminal output. Colors follow picocli's {@code Ansi.AUTO}: they are only used on a TTY and are
 * disabled by {@code NO_COLOR}. Progress and results go to stdout, warnings and errors to stderr.
 */
public final class Ui {

    private Ui() {
    }

    public static void heading(String text) {
        System.out.println();
        System.out.println(Ansi.AUTO.string("@|bold,underline " + escape(text) + "|@"));
    }

    public static void info(String text) {
        System.out.println(text);
    }

    public static void blank() {
        System.out.println();
    }

    public static void success(String text) {
        System.out.println(Ansi.AUTO.string("@|green ✓|@ " + escape(text)));
    }

    public static void warn(String text) {
        System.err.println(Ansi.AUTO.string("@|yellow,bold WARNING:|@ " + escape(text)));
    }

    public static void error(String text) {
        System.err.println(Ansi.AUTO.string("@|red,bold ERROR:|@ " + escape(text)));
    }

    /** A command the user is expected to copy and run. */
    public static void command(String text) {
        System.out.println(Ansi.AUTO.string("  @|cyan " + escape(text) + "|@"));
    }

    /** Prints instructions; lines indented by two spaces are commands to run. */
    public static void lines(List<String> lines) {
        lines.forEach(line -> {
            if (line.startsWith("  ")) {
                command(line.strip());
            } else {
                info(line);
            }
        });
    }

    /** Picocli markup uses '|@' and '@|' as delimiters, user values must not break out of it. */
    private static String escape(String text) {
        return text.replace("@|", "@ |").replace("|@", "| @");
    }
}

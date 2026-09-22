package bio.cosy.flnet.cli.helper;

import picocli.CommandLine.Help.Ansi;

import java.util.List;

public final class ConsoleHelper {

    private ConsoleHelper() {
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

    public static void command(String text) {
        System.out.println(Ansi.AUTO.string("  @|cyan " + escape(text) + "|@"));
    }

    public static void lines(List<String> lines) {
        lines.forEach(line -> {
            if (line.startsWith("  ")) {
                command(line.strip());
            } else {
                info(line);
            }
        });
    }

    private static String escape(String text) {
        return text.replace("@|", "@ |").replace("|@", "| @");
    }
}

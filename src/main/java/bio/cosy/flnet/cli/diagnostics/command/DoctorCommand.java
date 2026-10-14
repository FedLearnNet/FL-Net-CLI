package bio.cosy.flnet.cli.diagnostics.command;

import bio.cosy.flnet.cli.helper.ProcessHelper;
import bio.cosy.flnet.cli.helper.VersionProvider;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;

import java.util.Optional;
import java.util.concurrent.Callable;

@Command(name = "doctor", description = "Check that everything needed to run FL-Net is installed.")
public class DoctorCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("flnet " + VersionProvider.version());
        System.out.println();
        boolean ok = check(true, "docker", "missing", "needed to run platform and client", "docker", "--version");
        ok &= check(true, "docker compose", "missing", "v2 plugin, needed to run platform and client", "docker", "compose", "version", "--short");
        if (ProcessHelper.probe("docker", "compose", "version", "--short").filter(DoctorCommand::isBeforeComposeReset).isPresent()) {
            System.out.println(Ansi.AUTO.string("@|yellow -|@ " + String.format("%-15s %s", "", "older than 2.24")
                    + "  @|faint ('flnet sandbox' needs 2.24 or newer)|@"));
        }
        ok &= check(true, "docker daemon", "not reachable", "start Docker, and make sure your user may access it", "docker", "info", "--format", "{{.ServerVersion}}");
        check(false, "openssl", "not found (optional)", "only for 'flnet client certs'", "openssl", "version");
        check(false, "python3", "not found (optional)", "for developing tools and 'flnet sandbox seed'", "python3", "--version");
        System.out.println();
        if (ok) {
            System.out.println(Ansi.AUTO.string("@|green,bold All required prerequisites are met.|@"));
            return 0;
        }
        System.out.println(Ansi.AUTO.string("@|red,bold Some required prerequisites are missing.|@ Install guide: https://docs.docker.com/engine/install/"));
        return 3;
    }

    /** docker compose understands '!reset' in override files, which the sandbox uses, since 2.24. */
    static boolean isBeforeComposeReset(String version) {
        String[] parts = version.strip().replaceFirst("^v", "").split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            return major < 2 || (major == 2 && parts.length > 1 && Integer.parseInt(parts[1].replaceAll("\\D.*", "")) < 24);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean check(boolean required, String name, String failure, String purpose, String... command) {
        Optional<String> result = ProcessHelper.probe(command);
        String mark = result.isPresent() ? "@|green ✓|@" : required ? "@|red ✗|@" : "@|yellow -|@";
        String detail = result.map(String::strip).filter(s -> !s.isEmpty()).orElse(failure);
        System.out.println(Ansi.AUTO.string(String.format("%s %-15s %s", mark, name, detail))
                + Ansi.AUTO.string("  @|faint (" + purpose + ")|@"));
        return result.isPresent() || !required;
    }
}

package bio.cosy.flnet.cli.deploy;

import bio.cosy.flnet.cli.base.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.base.EnvVariable;
import bio.cosy.flnet.cli.base.FLNetClientDeployment;
import bio.cosy.flnet.cli.base.FLNetPlatformDeployment;
import bio.cosy.flnet.cli.support.EnvFile;
import bio.cosy.flnet.cli.support.Ui;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Prints deployments as a table or in detail. */
public final class InstanceReport {

    private InstanceReport() {
    }

    public static void table(ComposeBO composeBO, List<? extends BaseFLNetDeployableInstance> instances) {
        String format = "%-10s %-14s %-42s %-14s %s";
        System.out.println(String.format(format, "KIND", "NAME", "ADDRESS", "PORTS", "STATUS"));
        for (BaseFLNetDeployableInstance instance : instances) {
            System.out.println(String.format(format, instance.getKind().bundle(), instance.getName(), instance.getAddress(),
                    instance.getPorts().stream().map(String::valueOf).collect(Collectors.joining(",")),
                    composeBO.containerState(instance)));
        }
    }

    public static void details(ComposeBO composeBO, BaseFLNetDeployableInstance instance, String nameFlag) {
        Ui.heading(instance.getKind().displayName() + " '" + instance.getName() + "'");
        row("Status", composeBO.containerState(instance));
        row("Directory", instance.getDirectory().toString());
        row("Compose project", instance.getProjectName());
        if (instance instanceof FLNetClientDeployment client) {
            row("Address", client.getAddress());
            row("Listens on", client.getBindIp() + ":" + client.getPort());
            row("Network", (client.getPlatformAddress() == null ? "?" : client.getPlatformAddress().toString())
                    + " (relay port " + client.getPlatformRelayPort() + ")");
            row("Federation", client.isFederation() ? "enabled" : "disabled");
        } else if (instance instanceof FLNetPlatformDeployment platform) {
            row("Domain", platform.getAddress());
            row("nginx", platform.getBindIp() + ":" + platform.getNginxPort());
            row("Relay", "0.0.0.0:" + platform.getRelayPort());
            row("Min. clients", String.valueOf(platform.getMinClients()));
            row("Client auth", platform.isClientAuth() ? "required" : "off");
        }
        row("SSL in nginx", instance.isSslEnabled() ? String.valueOf(instance.getSslCertificate()) : "no");
        row("Keycloak admin", instance.getSecretsDirectory().resolve("keycloak-secrets.env").toString());
        Ui.blank();
        Ui.info("Manage it with:");
        Ui.command("flnet " + instance.getKind().bundle() + " up|down|pull|stop|restart|status|logs|compose|clean" + nameFlag);
        Ui.command("flnet " + instance.getKind().bundle() + " init" + (nameFlag.isEmpty() ? " --name " + instance.getName() : nameFlag)
                + "    (reconfigure)");
    }

    /** Every .env variable with its value and what it means. */
    public static void env(BaseFLNetDeployableInstance instance) {
        Ui.heading(".env of " + instance.getKind().displayName() + " '" + instance.getName() + "' (" + instance.getEnvFile() + ")");
        Map<String, String> values = EnvFile.read(instance.getEnvFile());
        for (EnvVariable variable : instance.getEnvVariables()) {
            System.out.println("  " + variable.key() + "=" + variable.in(values, ""));
            System.out.println("      " + variable.description());
        }
    }

    private static void row(String label, String value) {
        System.out.println(String.format("  %-16s %s", label, value));
    }
}

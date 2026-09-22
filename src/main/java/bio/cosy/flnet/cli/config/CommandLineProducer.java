package bio.cosy.flnet.cli.config;

import bio.cosy.flnet.cli.network.FLNetNetwork;
import bio.cosy.flnet.cli.network.bo.FLNetNetworkBO;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import io.quarkus.picocli.runtime.PicocliCommandLineFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.Config;
import picocli.CommandLine;

import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TreeMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class CommandLineProducer {

    @Produces
    CommandLine commandLine(PicocliCommandLineFactory factory, Config config, FLNetNetworkBO networks) {
        return factory.create()
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setResourceBundle(helpValues(config, networks))
                .setExecutionExceptionHandler(CommandLineProducer::handle);
    }
    private static ResourceBundle helpValues(Config config, FLNetNetworkBO networks) {
        Map<String, String> values = new TreeMap<>();
        for (String name : config.getPropertyNames()) {
            if (name.startsWith("flnet.")) {
                config.getOptionalValue(name, String.class).ifPresent(value -> values.put(name, value));
            }
        }
        values.put("flnet.network-keys", networks.all().stream().map(FLNetNetwork::getKey).collect(Collectors.joining(", ")));
        return new ResourceBundle() {
            @Override
            protected Object handleGetObject(String key) {
                return values.get(key);
            }

            @Override
            public Enumeration<String> getKeys() {
                return Collections.enumeration(values.keySet());
            }
        };
    }

    private static int handle(Exception e, CommandLine cmd, CommandLine.ParseResult parseResult) {
        if (e instanceof CliException cli) {
            ConsoleHelper.error(cli.getMessage());
            return cli.exitCode();
        }
        if (e instanceof UncheckedIOException io) {
            ConsoleHelper.error(io.getMessage() + ": " + io.getCause().getMessage());
        } else {
            ConsoleHelper.error("Unexpected error: " + e);
        }
        if ("true".equalsIgnoreCase(System.getenv("FLNET_DEBUG"))) {
            e.printStackTrace();
        } else {
            System.err.println("Run with FLNET_DEBUG=true for details.");
        }
        return 1;
    }
}

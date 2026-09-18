package bio.cosy.flnet.cli;

import bio.cosy.flnet.cli.config.FLNetNetwork;
import bio.cosy.flnet.cli.config.Networks;
import bio.cosy.flnet.cli.support.CliException;
import bio.cosy.flnet.cli.support.Ui;
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
    CommandLine commandLine(PicocliCommandLineFactory factory, Config config, Networks networks) {
        return factory.create()
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setResourceBundle(helpValues(config, networks))
                .setExecutionExceptionHandler(CommandLineProducer::handle);
    }

    /**
     * Makes the effective configuration available in help texts as {@code ${bundle:flnet.client.port}},
     * so documented defaults always match the configured ones.
     */
    private static ResourceBundle helpValues(Config config, Networks networks) {
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

    /** Expected failures become a one line message; stack traces only with FLNET_DEBUG=true. */
    private static int handle(Exception e, CommandLine cmd, CommandLine.ParseResult parseResult) {
        if (e instanceof CliException cli) {
            Ui.error(cli.getMessage());
            return cli.exitCode();
        }
        if (e instanceof UncheckedIOException io) {
            Ui.error(io.getMessage() + ": " + io.getCause().getMessage());
        } else {
            Ui.error("Unexpected error: " + e);
        }
        if ("true".equalsIgnoreCase(System.getenv("FLNET_DEBUG"))) {
            e.printStackTrace();
        } else {
            System.err.println("Run with FLNET_DEBUG=true for details.");
        }
        return 1;
    }
}

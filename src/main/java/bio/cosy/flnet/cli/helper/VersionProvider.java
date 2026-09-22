package bio.cosy.flnet.cli.helper;

import org.eclipse.microprofile.config.ConfigProvider;
import picocli.CommandLine.IVersionProvider;

public class VersionProvider implements IVersionProvider {

    public static String version() {
        return ConfigProvider.getConfig().getOptionalValue("quarkus.application.version", String.class).orElse("unknown");
    }

    @Override
    public String[] getVersion() {
        return new String[]{"flnet " + version(), "${os.name} ${os.arch}, Java ${java.version}"};
    }
}

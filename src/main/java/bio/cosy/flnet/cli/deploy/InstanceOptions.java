package bio.cosy.flnet.cli.deploy;

import picocli.CommandLine.Option;

import java.nio.file.Path;

/** Mixin selecting the instance a command works on. */
public class InstanceOptions {

    @Option(names = {"-n", "--name"}, paramLabel = "<name>",
            description = "Instance name (several clients/platforms can run on one server). "
                    + "Optional when only one exists; see 'list'.")
    public String name;

    @Option(names = {"-d", "--dir"}, paramLabel = "<dir>",
            description = "Use the deployment in this directory instead of $FLNET_HOME/<kind>s/<name>.")
    public Path dir;
}

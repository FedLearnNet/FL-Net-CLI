package bio.cosy.flnet.cli.helper;

import picocli.CommandLine.Option;

public class InteractionOptions {

    @Option(names = {"--no-input", "--no-interactive"},
            description = "Never prompt. Every value comes from flags or defaults; missing required values are an error. "
                    + "Implied when no interactive terminal is attached.")
    public boolean noInput;

    @Option(names = {"-y", "--yes"},
            description = "Accept all confirmations and skip 'press Enter' pauses.")
    public boolean yes;
}

package bio.cosy.flnet.cli.support;

import picocli.CommandLine.Option;

/** Mixin shared by every command that may ask questions or confirmations. */
public class InteractionOptions {

    @Option(names = "--no-input",
            description = "Never prompt. Every value comes from flags or defaults; missing required values are an error. "
                    + "Implied when no interactive terminal is attached.")
    public boolean noInput;

    @Option(names = {"-y", "--yes"},
            description = "Accept all confirmations and skip 'press Enter' pauses.")
    public boolean yes;
}

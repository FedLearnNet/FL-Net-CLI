package bio.cosy.flnet.cli.sandbox.command;

import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.deploy.bo.ComposeBO;
import bio.cosy.flnet.cli.helper.CliException;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.sandbox.FLNetSandbox;
import bio.cosy.flnet.cli.sandbox.SandboxImage;
import bio.cosy.flnet.cli.sandbox.bo.FLNetSandboxBO;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Command(name = "images",
        description = {
                "List the images the sandbox runs. With 'set' and 'reset', replace the image of a FL-Net service,",
                "e.g. with one you built yourself, for the platform and all clients at once."
        },
        subcommands = {
                SandboxImagesCommand.Set.class,
                SandboxImagesCommand.Reset.class,
        },
        footer = {
                "",
                "Examples:",
                "  flnet sandbox images",
                "  flnet sandbox images set local-learning-api local-learning-api:dev",
                "  flnet sandbox images reset local-learning-api",
                "  flnet sandbox images reset"
        })
public class SandboxImagesCommand implements Callable<Integer> {

    static final String SERVICE = "One of: orch-api, local-learning-api, global-learning-api, datamodeler-api, controller, "
            + "relay-server, local-frontend, global-frontend, pipeline-builder.";

    @Inject
    FLNetSandboxBO sandboxBO;

    @Inject
    ComposeBO composeBO;

    @Override
    public Integer call() {
        FLNetSandbox sandbox = sandboxBO.require();
        ConsoleHelper.heading("Replaced images");
        if (sandbox.getImages().isEmpty()) {
            ConsoleHelper.info("None: every service runs the published image with tag '" + sandbox.getPlatform().getImageTag() + "'.");
        }
        sandbox.getImages().forEach((image, value) -> System.out.println(String.format("  %-20s %s", image.key(), value)));
        for (BaseFLNetDeployableInstance member : sandbox.getMembers()) {
            ConsoleHelper.heading("Images of " + member.getName());
            composeBO.images(member).forEach(image -> System.out.println("  " + image
                    + (sandbox.getImages().containsValue(image) ? "   (replaced)" : "")));
        }
        ConsoleHelper.blank();
        ConsoleHelper.info("Replace the image of a service (" + SandboxImage.keys() + "):");
        ConsoleHelper.command("flnet sandbox images set <service> <image>");
        return 0;
    }

    static SandboxImage service(String key) {
        return SandboxImage.byKey(key).orElseThrow(() -> CliException.usage("Unknown service '" + key + "'. " + SERVICE));
    }

    static void applyHint() {
        ConsoleHelper.info("Apply it (recreates the changed containers):");
        ConsoleHelper.command("flnet sandbox up");
    }

    @Command(name = "set", description = {"Run another image for a service, in the platform and in all clients that have it.",
            "The image is not pulled by 'flnet sandbox up', so it only has to exist on this machine."})
    public static class Set implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "<service>", description = SERVICE)
        String service;

        @Parameters(index = "1", paramLabel = "<image>", description = "Image name with tag, e.g. local-learning-api:dev.")
        String image;

        @Inject
        FLNetSandboxBO sandboxBO;

        @Override
        public Integer call() {
            if (image.isBlank() || image.chars().anyMatch(Character::isWhitespace)) {
                throw CliException.usage("Invalid image '" + image + "'.");
            }
            SandboxImage target = service(service);
            sandboxBO.setImage(sandboxBO.require(), target, image);
            ConsoleHelper.success(target.key() + " now uses " + image + ".");
            applyHint();
            return 0;
        }
    }

    @Command(name = "reset", description = "Use the published image again, for one service or (without a service) for all.")
    public static class Reset implements Callable<Integer> {

        @Parameters(index = "0", arity = "0..1", paramLabel = "<service>", description = SERVICE)
        String service;

        @Inject
        FLNetSandboxBO sandboxBO;

        @Override
        public Integer call() {
            SandboxImage target = service == null ? null : service(service);
            sandboxBO.resetImages(sandboxBO.require(), target);
            ConsoleHelper.success((target == null ? "All services use" : target.key() + " uses") + " the published image again.");
            applyHint();
            return 0;
        }
    }
}

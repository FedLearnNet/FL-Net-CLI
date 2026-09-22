package bio.cosy.flnet.cli.platform.command;

import bio.cosy.flnet.cli.base.deployment.FLNetPlatformDeployment;
import bio.cosy.flnet.cli.deploy.command.BaseInitCommand;
import bio.cosy.flnet.cli.platform.bo.FLNetPlatformDeploymentBO;
import bio.cosy.flnet.cli.platform.config.PlatformConfig;
import bio.cosy.flnet.cli.platform.questionnaire.PlatformQuestionnaire;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(name = "init",
        description = {
                "Create or reconfigure a FL-Net Platform deployment directory.",
                "Asks for every setting that is not given as a flag. Existing secrets are kept.",
                "Several platforms can run on one machine: give each a --name, ports are chosen to not collide."
        },
        footer = {
                "",
                "Examples:",
                "  flnet platform init",
                "  flnet platform init --no-input --domain https://fl.example.org \\",
                "      --ssl-cert /etc/letsencrypt/live/fl.example.org/fullchain.pem \\",
                "      --ssl-key /etc/letsencrypt/live/fl.example.org/privkey.pem"
        })
public class PlatformInitCommand extends BaseInitCommand {

    @Mixin
    PlatformConfig given;

    @Inject
    PlatformQuestionnaire questionnaire;

    @Inject
    FLNetPlatformDeploymentBO platformBO;

    @Override
    public Integer call() {
        FLNetPlatformDeployment platform = prepare(platformBO, given);
        questionnaire.ask(given, platform);
        write(platformBO, platform, given);
        nextSteps(platformBO.nextSteps(platform));
        return 0;
    }
}

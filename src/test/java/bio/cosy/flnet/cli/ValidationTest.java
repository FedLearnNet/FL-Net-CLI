package bio.cosy.flnet.cli;

import bio.cosy.flnet.cli.base.deployment.FLNetClientDeployment;
import bio.cosy.flnet.cli.base.deployment.AutoAccess;
import bio.cosy.flnet.cli.base.deployment.SslSource;
import bio.cosy.flnet.cli.helper.CliException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ValidationTest {

    @Inject
    Validator validator;

    @Test
    void reportsEveryProblemInEnglish() {
        FLNetClientDeployment client = new FLNetClientDeployment();
        client.setName("Site_A");
        client.setDirectory(Path.of("/tmp/flnet/clients/site-a"));
        client.setListen("example.org");
        client.useSsl(SslSource.PROVIDED, Path.of("c.pem"), Path.of("k.pem"));
        client.setAutoMetrics(AutoAccess.ALL);
        client.setPort(70000);

        List<String> problems = client.problems(validator);
        assertEquals(List.of(
                "Automatic access in the default permission requires allowing automatic permissions for that resource.",
                "Invalid instance name. Use 1-32 lowercase letters, digits and dashes, starting and ending with a letter or digit (e.g. 'site-a').",
                "Ports must be between 1 and 65535.",
                "SSL termination in the client requires a domain.",
                "Secrets are missing; generate them before saving.",
                "The compose project name is required.",
                "The frontend image is required.",
                "The listen address must be 'localhost' or an IPv4 address.",
                "The platform address is required.",
                "The platform relay port must be between 1 and 65535.",
                "The platform requires authentication, so a platform username and password are required."), problems);

        CliException error = assertThrows(CliException.class, () -> client.requireValid(validator));
        assertEquals(CliException.USAGE, error.exitCode());
        assertTrue(error.getMessage().startsWith("Invalid client configuration: "), error.getMessage());
    }
}

package bio.cosy.flnet.cli.client.config;

import bio.cosy.flnet.cli.base.deployment.AutoAccess;
import bio.cosy.flnet.cli.helper.WebAddress;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ClientConfigMapperTest {

    @Inject
    ClientConfigMapper mapper;

    @Test
    void appliesGivenOptionsOntoTheTarget() {
        ClientConfig options = new ClientConfig();
        options.setPlatformUrl("https://fl.example.org");
        options.setPlatformRelayPort(9999);
        options.setAllowAutoStatistics(true);
        options.setAutoStatistics("all");
        options.setQueryRetryTime(42);

        PersistentClientConfig target = new PersistentClientConfig();
        mapper.updateFromOptions(options, target);

        assertEquals(WebAddress.parse("https://fl.example.org"), target.getPlatformAddress());
        assertEquals(9999, target.getPlatformRelayPort());
        assertTrue(target.isAllowAutoStatistics());
        assertEquals(AutoAccess.ALL, target.getAutoStatistics());
        assertEquals(42, target.getQueryRetryTime());
    }

    @Test
    void unsetOptionsKeepTheTargetsCurrentValues() {
        ClientConfig options = new ClientConfig();

        PersistentClientConfig target = new PersistentClientConfig();
        target.setPlatformRelayPort(1234);
        target.setAllowAutoStatistics(true);
        mapper.updateFromOptions(options, target);

        assertEquals(1234, target.getPlatformRelayPort());
        assertTrue(target.isAllowAutoStatistics());
        assertTrue(target.isFederation());
    }
}

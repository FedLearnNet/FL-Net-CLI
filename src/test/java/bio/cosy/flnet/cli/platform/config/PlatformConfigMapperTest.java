package bio.cosy.flnet.cli.platform.config;

import bio.cosy.flnet.cli.helper.WebAddress;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PlatformConfigMapperTest {

    @Inject
    PlatformConfigMapper mapper;

    @Test
    void appliesGivenOptionsOntoTheTarget() {
        PlatformConfig options = new PlatformConfig();
        options.setDomain("https://fl.example.org");
        options.setBindIp("0.0.0.0");
        options.setPort(8443);
        options.setRelayPort(9153);
        options.setMinClients(5);
        options.setClientAuth(false);

        PersistentPlatformConfig target = new PersistentPlatformConfig();
        mapper.updateFromOptions(options, target);

        assertEquals(WebAddress.parse("https://fl.example.org"), target.getDomain());
        assertEquals("0.0.0.0", target.getBindIp());
        assertEquals(8443, target.getNginxPort());
        assertEquals(9153, target.getRelayPort());
        assertEquals(5, target.getMinClients());
        assertEquals(false, target.isClientAuth());
    }

    @Test
    void unsetOptionsKeepTheTargetsCurrentValues() {
        PlatformConfig options = new PlatformConfig();

        PersistentPlatformConfig target = new PersistentPlatformConfig();
        target.setNginxPort(1234);
        mapper.updateFromOptions(options, target);

        assertEquals(1234, target.getNginxPort());
        assertTrue(target.isClientAuth());
    }
}

package bio.cosy.flnet.cli.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebAddressTest {

    @Test
    void parsesDefaultPorts() {
        WebAddress https = WebAddress.parse("https://Fl.Example.org/");
        assertEquals("https", https.protocol());
        assertEquals(443, https.port());
        assertTrue(https.isDefaultPort());
        assertEquals("https://Fl.Example.org", https.toString());

        WebAddress http = WebAddress.parse("HTTP://localhost");
        assertEquals(80, http.port());
        assertEquals("http://localhost", http.toString());
    }

    @Test
    void keepsNonDefaultPorts() {
        WebAddress address = WebAddress.parse("https://fl.example.org:8443");
        assertEquals("fl.example.org:8443", address.hostWithPort());
        assertEquals("https://fl.example.org:8443", address.toString());
        // the Python installer printed https:// for http addresses; the protocol must be kept
        assertEquals("http://localhost:8080", WebAddress.parse("http://localhost:8080").toString());
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> WebAddress.parse("fl.example.org"));
        assertThrows(IllegalArgumentException.class, () -> WebAddress.parse("ftp://fl.example.org"));
        assertThrows(IllegalArgumentException.class, () -> WebAddress.parse("https://fl.example.org:70000"));
        assertThrows(IllegalArgumentException.class, () -> WebAddress.parse("https://-bad.org"));
        assertThrows(IllegalArgumentException.class, () -> WebAddress.parse("https://a..b"));
        assertThrows(IllegalArgumentException.class, () -> WebAddress.parse("https://under_score.org"));
        assertNotNull(WebAddress.validate("nope"));
        assertNull(WebAddress.validate("https://ok.org"));
    }

    @Test
    void detectsIpAddresses() {
        assertTrue(WebAddress.parse("https://10.0.0.5").isIpAddress());
        assertFalse(WebAddress.parse("https://fl.example.org").isIpAddress());
    }

    @Test
    void validatesNetworkValues() {
        assertTrue(Net.isPort("1"));
        assertTrue(Net.isPort("65535"));
        assertFalse(Net.isPort("0"));
        assertFalse(Net.isPort("65536"));
        assertFalse(Net.isPort("http"));
        assertTrue(Net.isIpv4("0.0.0.0"));
        assertTrue(Net.isIpv4("192.168.1.100"));
        assertFalse(Net.isIpv4("256.1.1.1"));
        assertFalse(Net.isIpv4("1.2.3"));
        assertFalse(Net.isIpv4("localhost"));
        // legacy forms that Inet4Address.ofLiteral would accept
        assertFalse(Net.isIpv4("8250"));
        assertFalse(Net.isIpv4("010.0.0.1"));
    }
}

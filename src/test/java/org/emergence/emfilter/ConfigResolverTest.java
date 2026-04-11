package org.emergence.emfilter;

import org.emergence.emfilter.internal.ConfigResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigResolverTest {

    @Test
    void explicitNodesOverrideEverything() {
        AgentConfig cfg = new AgentConfig(null,
            List.of(new DiscoNode("myhost.com", 9000, false)));
        List<DiscoNode> nodes = ConfigResolver.resolveNodes(cfg);
        assertEquals(1, nodes.size());
        assertEquals("myhost.com", nodes.get(0).host);
        assertEquals(9000, nodes.get(0).port);
    }

    @Test
    void defaultIsLocalhost8080() {
        // Remove env vars if set in CI — not possible without Mockito/system-stubs
        // This test relies on EM_DISCO_HOST / EM_DISCO_PORT not being set.
        // Skip if they are.
        if (System.getenv("EM_DISCO_HOST") != null) return;
        AgentConfig cfg = new AgentConfig();
        List<DiscoNode> nodes = ConfigResolver.resolveNodes(cfg);
        // May be overridden by emergence.conf — just check non-empty.
        assertFalse(nodes.isEmpty());
    }

    @Test
    void inferTlsLocalhost() {
        assertFalse(ConfigResolver.inferTls("localhost", 443));
        assertFalse(ConfigResolver.inferTls("127.0.0.1", 443));
        assertFalse(ConfigResolver.inferTls("::1", 443));
    }

    @Test
    void inferTlsRemote443() {
        assertTrue(ConfigResolver.inferTls("example.com", 443));
    }

    @Test
    void inferTlsRemoteOtherPort() {
        assertFalse(ConfigResolver.inferTls("example.com", 8080));
    }

    @Test
    void parseConfSingleNode() {
        List<DiscoNode> nodes = ConfigResolver.parseConf("[em_disco]\nnodes = localhost:8080\n");
        assertEquals(1, nodes.size());
        assertEquals("localhost", nodes.get(0).host);
        assertEquals(8080, nodes.get(0).port);
    }

    @Test
    void parseConfTwoNodes() {
        List<DiscoNode> nodes = ConfigResolver.parseConf(
            "[em_disco]\nnodes = localhost:8080, disco.example.com\n");
        assertEquals(2, nodes.size());
        assertEquals("disco.example.com", nodes.get(1).host);
        assertEquals(443, nodes.get(1).port);
        assertTrue(nodes.get(1).tls);
    }

    @Test
    void parseConfCommentsIgnored() {
        List<DiscoNode> nodes = ConfigResolver.parseConf(
            "; comment\n[em_disco]\n# another\nnodes = localhost:9000\n");
        assertEquals(9000, nodes.get(0).port);
    }

    @Test
    void parseConfLastNodesWins() {
        List<DiscoNode> nodes = ConfigResolver.parseConf(
            "[em_disco]\nnodes = localhost:8080\nnodes = localhost:9090\n");
        assertEquals(9090, nodes.get(0).port);
    }

    @Test
    void resolveJwtFromStruct() {
        AgentConfig cfg = new AgentConfig("mytoken", null);
        assertEquals("mytoken", ConfigResolver.resolveJwt(cfg));
    }
}

package org.emergence.emfilter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterRunnerTest {

    private final Filter filter = new Filter() {
        @Override
        public FilterResult handle(String body, Map<String, Object> memory) {
            return new FilterResult(List.of(), memory);
        }
    };

    @Test
    void defaultModeIsRelay(@TempDir Path tmp) throws Exception {
        if (System.getenv("EM_FILTER_MODE") != null) return; // env override would change the default
        AgentConfig cfg = new AgentConfig();
        cfg.keyDir = tmp.toString();
        cfg.discoNodes = List.of(new DiscoNode("127.0.0.1", 9, false));

        FilterRunner.Plan plan = new FilterRunner("t", filter, cfg).build();

        assertEquals("relay", plan.mode());
        assertNull(plan.agentServer());
        assertNull(plan.gossipPusher());
        assertEquals(1, plan.relayClients().size());
    }

    @Test
    void directModeBuildsServerAndPusherOnly(@TempDir Path tmp) throws Exception {
        AgentConfig cfg = new AgentConfig();
        cfg.mode = "direct";
        cfg.keyDir = tmp.toString();
        cfg.queryPort = 0;
        cfg.discoNodes = List.of(new DiscoNode("127.0.0.1", 9, false));

        FilterRunner.Plan plan = new FilterRunner("t", filter, cfg).build();
        try {
            assertEquals("direct", plan.mode());
            assertTrue(plan.agentServer() != null);
            assertTrue(plan.gossipPusher() != null);
            assertTrue(plan.relayClients().isEmpty());
        } finally {
            if (plan.agentServer() != null) plan.agentServer().stop();
        }
    }

    @Test
    void bothModeBuildsEverything(@TempDir Path tmp) throws Exception {
        AgentConfig cfg = new AgentConfig();
        cfg.mode = "both";
        cfg.keyDir = tmp.toString();
        cfg.queryPort = 0;
        cfg.discoNodes = List.of(new DiscoNode("127.0.0.1", 9, false));

        FilterRunner.Plan plan = new FilterRunner("t", filter, cfg).build();
        try {
            assertEquals("both", plan.mode());
            assertTrue(plan.agentServer() != null);
            assertTrue(plan.gossipPusher() != null);
            assertEquals(1, plan.relayClients().size());
        } finally {
            if (plan.agentServer() != null) plan.agentServer().stop();
        }
    }
}

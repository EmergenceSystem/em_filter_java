package org.emergence.emfilter;

import org.emergence.emfilter.internal.ConfigResolver;
import org.emergence.emfilter.internal.Connection;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Starts one connection thread per resolved disco node and blocks until they all exit.
 *
 * <p>Mirrors Erlang's {@code em_filter:start_agent/3} followed by a blocking join.
 *
 * <pre>{@code
 * new FilterRunner("my_filter", new MyFilter(), new AgentConfig()).run();
 * }</pre>
 */
public final class FilterRunner {

    private static final Logger log = Logger.getLogger(FilterRunner.class.getName());

    private final String      name;
    private final Filter      filter;
    private final AgentConfig config;

    public FilterRunner(String name, Filter filter, AgentConfig config) {
        this.name   = name;
        this.filter = filter;
        this.config = config != null ? config : new AgentConfig();
    }

    /** Start all connection threads and block until they all exit (never in normal operation). */
    public void run() throws InterruptedException {
        List<DiscoNode> nodes = ConfigResolver.resolveNodes(config);
        String          jwt   = ConfigResolver.resolveJwt(config);
        long reconnectMs = Long.parseLong(
            System.getenv().getOrDefault("EM_FILTER_RECONNECT_MS", "5000"));
        List<String> caps = filter.capabilities();

        log.info("[em_filter] Starting agent '" + name + "' on " + nodes.size() + " node(s)");

        List<Thread> threads = new ArrayList<>();
        for (DiscoNode node : nodes) {
            Connection conn = new Connection(name, node, filter, caps, jwt, reconnectMs);
            Thread t = new Thread(conn, name + "@" + node.host + ":" + node.port);
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }

        for (Thread t : threads) t.join();
    }
}

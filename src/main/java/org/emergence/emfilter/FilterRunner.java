package org.emergence.emfilter;

import org.emergence.emfilter.internal.ConfigResolver;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

/**
 * Dispatches to the configured transport(s) and runs them.
 *
 * <p>{@code EM_FILTER_MODE} (or {@link AgentConfig#mode}) selects the transport:
 * {@code relay} (default, NAT-friendly outbound WS), {@code direct} (inbound HTTP
 * + gossip push), or {@code both}.
 *
 * <pre>{@code
 * new FilterRunner("my_filter", new MyFilter(), new AgentConfig()).run();
 * }</pre>
 */
public final class FilterRunner {

    private static final Logger log = Logger.getLogger(FilterRunner.class.getName());

    private static final String DEFAULT_MODE = "relay";
    private static final int DEFAULT_QUERY_PORT = 9600;
    private static final String DEFAULT_ADVERTISE_HOST = "127.0.0.1";
    private static final long DEFAULT_GOSSIP_INTERVAL_MS = 5000;
    private static final long DEFAULT_RECONNECT_MS = 5000;

    private final String name;
    private final Filter filter;
    private final AgentConfig config;

    public FilterRunner(String name, Filter filter, AgentConfig config) {
        this.name = name;
        this.filter = filter;
        this.config = config != null ? config : new AgentConfig();
    }

    /** The wired-up transport(s) for the resolved mode, not yet started. */
    public record Plan(String mode, Identity identity, AgentServer agentServer,
                        GossipPusher gossipPusher, List<RelayClient> relayClients) {}

    /** Resolve mode/config and wire the transport(s), without starting anything. */
    public Plan build() throws Exception {
        String mode = resolveMode();
        Path keyDir = resolveKeyDir();
        Identity identity = new Identity(name, keyDir, filter.capabilities());
        List<DiscoNode> nodes = ConfigResolver.resolveNodes(config);

        AgentServer server = null;
        GossipPusher pusher = null;
        List<RelayClient> relays = new ArrayList<>();

        if (isDirect(mode) || isBoth(mode)) {
            int queryPort = resolveQueryPort();
            server = new AgentServer(identity, filter, "0.0.0.0", queryPort);
            String advertiseHost = resolveAdvertiseHost();
            int advertisedPort = queryPort != 0 ? queryPort : server.port();
            pusher = new GossipPusher(identity, nodes, advertiseHost, advertisedPort, resolveGossipIntervalMs());
        }
        if (isRelay(mode) || isBoth(mode)) {
            long reconnectMs = resolveReconnectMs();
            for (DiscoNode node : nodes) {
                relays.add(new RelayClient(identity, filter, relayUri(node), reconnectMs));
            }
        }
        return new Plan(mode, identity, server, pusher, Collections.unmodifiableList(relays));
    }

    /** Start the resolved transport(s) and block until they all exit (never in normal operation). */
    public void run() throws Exception {
        Plan plan = build();
        log.info("[em_filter] Starting agent '" + name + "' in mode '" + plan.mode() + "'");

        List<Thread> threads = new ArrayList<>();
        if (plan.agentServer() != null) {
            plan.agentServer().start();
            log.info("[em_filter] " + name + " serving /agent/query on port " + plan.agentServer().port());
            if (plan.gossipPusher() != null) plan.gossipPusher().start();
        }
        for (RelayClient rc : plan.relayClients()) {
            Thread t = new Thread(rc::runForever, name + "-relay");
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }

        if (threads.isEmpty()) {
            // Direct-only: the server and gossip pusher already run on their own
            // threads, so just block the caller forever.
            new CountDownLatch(1).await();
        } else {
            for (Thread t : threads) t.join();
        }
    }

    private static boolean isDirect(String mode) { return "direct".equals(mode); }
    private static boolean isRelay(String mode) { return "relay".equals(mode); }
    private static boolean isBoth(String mode) { return "both".equals(mode); }

    private String resolveMode() {
        if (config.mode != null && !config.mode.isBlank()) return config.mode;
        String env = System.getenv("EM_FILTER_MODE");
        return (env != null && !env.isBlank()) ? env : DEFAULT_MODE;
    }

    private Path resolveKeyDir() {
        if (config.keyDir != null && !config.keyDir.isBlank()) return Path.of(config.keyDir);
        String env = System.getenv("EM_FILTER_KEY_DIR");
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of("empop_key_" + name);
    }

    private int resolveQueryPort() {
        if (config.queryPort != null) return config.queryPort;
        String env = System.getenv("EM_FILTER_QUERY_PORT");
        if (env != null && !env.isBlank()) {
            try { return Integer.parseInt(env.trim()); } catch (NumberFormatException ignored) { /* fall through */ }
        }
        return DEFAULT_QUERY_PORT;
    }

    private String resolveAdvertiseHost() {
        if (config.advertiseHost != null && !config.advertiseHost.isBlank()) return config.advertiseHost;
        String env = System.getenv("EM_FILTER_HOST");
        return (env != null && !env.isBlank()) ? env : DEFAULT_ADVERTISE_HOST;
    }

    private long resolveGossipIntervalMs() {
        if (config.gossipIntervalMs != null) return config.gossipIntervalMs;
        String env = System.getenv("EM_FILTER_GOSSIP_INTERVAL_MS");
        if (env != null && !env.isBlank()) {
            try { return Long.parseLong(env.trim()); } catch (NumberFormatException ignored) { /* fall through */ }
        }
        return DEFAULT_GOSSIP_INTERVAL_MS;
    }

    private long resolveReconnectMs() {
        if (config.reconnectMs != null) return config.reconnectMs;
        String env = System.getenv("EM_FILTER_RECONNECT_MS");
        if (env != null && !env.isBlank()) {
            try { return Long.parseLong(env.trim()); } catch (NumberFormatException ignored) { /* fall through */ }
        }
        return DEFAULT_RECONNECT_MS;
    }

    private static URI relayUri(DiscoNode node) {
        String scheme = node.tls ? "wss" : "ws";
        return URI.create(scheme + "://" + node.host + ":" + node.port + "/ws/filter");
    }
}

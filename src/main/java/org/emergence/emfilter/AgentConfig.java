package org.emergence.emfilter;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for a filter agent.
 *
 * <p>All fields are optional. If {@code discoNodes} is empty, nodes are resolved
 * automatically (env vars → emergence.conf → localhost:8080).
 */
public final class AgentConfig {

    /**
     * JWT token passed as {@code ?token=<jwt>} on WebSocket upgrade.
     * If {@code null}, {@code EM_FILTER_JWT_TOKEN} env var is checked.
     */
    public String jwtToken;

    /**
     * Explicit list of disco nodes.
     * If empty, nodes are resolved automatically.
     */
    public List<DiscoNode> discoNodes;

    /**
     * Transport mode: {@code "relay"} (default, NAT-friendly), {@code "direct"},
     * or {@code "both"}. If {@code null}, {@code EM_FILTER_MODE} env var is
     * checked, else defaults to {@code "relay"}.
     */
    public String mode;

    /**
     * Directory holding {@code node_ed25519.key}. If {@code null},
     * {@code EM_FILTER_KEY_DIR} env var is checked, else defaults to
     * {@code ./empop_key_<name>/}.
     */
    public String keyDir;

    /**
     * Inbound port for the Model A HTTP server. If {@code null},
     * {@code EM_FILTER_QUERY_PORT} env var is checked, else defaults to 9600.
     */
    public Integer queryPort;

    /**
     * Host advertised to peers in gossip payloads (Model A). If {@code null},
     * {@code EM_FILTER_HOST} env var is checked, else defaults to {@code 127.0.0.1}.
     */
    public String advertiseHost;

    /** Gossip push interval, ms. Defaults to 5000 (spec default 5s). */
    public Long gossipIntervalMs;

    /** Relay/gossip reconnect delay, ms. Defaults to 5000. */
    public Long reconnectMs;

    public AgentConfig() {
        this.discoNodes = new ArrayList<>();
    }

    public AgentConfig(String jwtToken, List<DiscoNode> discoNodes) {
        this.jwtToken    = jwtToken;
        this.discoNodes  = discoNodes != null ? discoNodes : new ArrayList<>();
    }
}

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

    public AgentConfig() {
        this.discoNodes = new ArrayList<>();
    }

    public AgentConfig(String jwtToken, List<DiscoNode> discoNodes) {
        this.jwtToken    = jwtToken;
        this.discoNodes  = discoNodes != null ? discoNodes : new ArrayList<>();
    }
}

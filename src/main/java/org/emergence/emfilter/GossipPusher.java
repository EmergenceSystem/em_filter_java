package org.emergence.emfilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Model A gossip push loop: every {@code intervalMs}, POST this agent's self-payload
 * ({@link Identity#gossipPayload}) to each configured seed disco's {@code /pop/gossip}.
 */
public final class GossipPusher {

    private static final Logger log = Logger.getLogger(GossipPusher.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Identity identity;
    private final List<DiscoNode> seeds;
    private final String advertiseHost;
    private final int queryPort;
    private final long intervalMs;
    private final HttpClient client = HttpClient.newHttpClient();

    private volatile boolean running;
    private Thread thread;

    public GossipPusher(Identity identity, List<DiscoNode> seeds, String advertiseHost, int queryPort, long intervalMs) {
        this.identity = identity;
        this.seeds = seeds;
        this.advertiseHost = advertiseHost;
        this.queryPort = queryPort;
        this.intervalMs = intervalMs;
    }

    public void start() {
        running = true;
        thread = new Thread(this::loop, "em-filter-gossip-pusher");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        while (running) {
            pushOnce();
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** Push the self-payload to every configured seed once. */
    public void pushOnce() {
        JsonNode payload = identity.gossipPayload(advertiseHost, queryPort);
        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            log.log(Level.WARNING, "failed to encode gossip payload", e);
            return;
        }
        for (DiscoNode seed : seeds) {
            String scheme = seed.tls ? "https" : "http";
            URI uri = URI.create(scheme + "://" + seed.host + ":" + seed.port + "/pop/gossip");
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .header("content-type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            try {
                client.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                log.log(Level.FINE, "gossip push to " + uri + " failed", e);
            }
        }
    }
}

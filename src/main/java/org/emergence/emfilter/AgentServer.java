package org.emergence.emfilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Model A (direct) transport: the filter serves inbound HTTP.
 *
 * <ul>
 *   <li>{@code POST /agent/query} — body {@code {"query": "..."}}; runs the user
 *       handler and replies {@code {"results", "signer_id", "signature"}}, signed
 *       over {@link Crypto#canonicalResponse}. Malformed/absent query → 400;
 *       handler error → 500.</li>
 *   <li>{@code POST /pop/gossip} — accepts a remote peer payload into a minimal
 *       flat peer map, replies with this agent's own gossip self-payload.</li>
 *   <li>{@code GET /health} — liveness, replies {@code "ok"}.</li>
 * </ul>
 */
public final class AgentServer {

    private static final Logger log = Logger.getLogger(AgentServer.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Identity identity;
    private final Filter filter;
    private final HttpServer server;
    private final String advertiseHost;
    private final AtomicReference<Map<String, Object>> memory = new AtomicReference<>(new ConcurrentHashMap<>());
    private final ConcurrentMap<String, JsonNode> peers = new ConcurrentHashMap<>();

    public AgentServer(Identity identity, Filter filter, String host, int port) throws IOException {
        this.identity = identity;
        this.filter = filter;
        this.advertiseHost = host;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/agent/query", this::handleQuery);
        server.createContext("/pop/gossip", this::handleGossip);
        server.createContext("/health", this::handleHealth);
        server.setExecutor(Executors.newCachedThreadPool());
    }

    /** Bound port — resolved after construction even when {@code port=0} was requested. */
    public int port() {
        return server.getAddress().getPort();
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    private void handleQuery(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respondText(ex, 404, "not found");
            return;
        }
        byte[] raw = ex.getRequestBody().readAllBytes();

        String query;
        try {
            JsonNode req = MAPPER.readTree(raw);
            JsonNode q = req.get("query");
            if (q == null || !q.isTextual()) throw new IllegalArgumentException("missing query");
            query = q.asText();
        } catch (Exception e) {
            respondJson(ex, 400, Map.of("error", "bad query"));
            return;
        }

        Object data;
        try {
            FilterResult fr = filter.handle(query, memory.get());
            memory.set(fr.memory != null ? fr.memory : new ConcurrentHashMap<>());
            data = fr.data;
        } catch (Exception e) {
            log.log(Level.WARNING, "handler error", e);
            respondJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
            return;
        }

        JsonNode items = MAPPER.valueToTree(data);
        Crypto.SignedResponse sr;
        try {
            sr = identity.signResults(items);
        } catch (Exception e) {
            throw new IOException(e);
        }

        ObjectNode resp = MAPPER.createObjectNode();
        resp.set("results", items);
        resp.put("signer_id", sr.signerId());
        resp.put("signature", sr.signature());
        respondJson(ex, 200, resp);
    }

    private void handleGossip(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respondText(ex, 404, "not found");
            return;
        }
        byte[] raw = ex.getRequestBody().readAllBytes();
        try {
            JsonNode remote = MAPPER.readTree(raw);
            JsonNode idNode = remote.get("id");
            if (idNode != null && idNode.isTextual()) {
                peers.put(idNode.asText(), remote);
            }
        } catch (Exception e) {
            log.log(Level.FINE, "ignoring malformed gossip payload", e);
        }
        respondJson(ex, 200, identity.gossipPayload(advertiseHost, port()));
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respondText(ex, 404, "not found");
            return;
        }
        respondText(ex, 200, "ok");
    }

    private void respondJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] b = MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().add("content-type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private void respondText(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}

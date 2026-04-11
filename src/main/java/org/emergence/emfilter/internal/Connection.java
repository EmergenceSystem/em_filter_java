package org.emergence.emfilter.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.emergence.emfilter.DiscoNode;
import org.emergence.emfilter.Filter;
import org.emergence.emfilter.FilterResult;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

/**
 * Manages a single persistent WebSocket connection to one em_disco node.
 *
 * <p>Mirrors Erlang's {@code em_filter_server} gen_server:
 * one instance per disco node, memory persists across reconnections.
 */
public final class Connection implements Runnable {

    private static final Logger log = Logger.getLogger(Connection.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String       agentName;
    private final DiscoNode    node;
    private final Filter       filter;
    private final List<String> capabilities;
    private final String       jwtToken;
    private final long         reconnectMs;

    public Connection(String agentName, DiscoNode node, Filter filter,
                      List<String> capabilities, String jwtToken, long reconnectMs) {
        this.agentName    = agentName;
        this.node         = node;
        this.filter       = filter;
        this.capabilities = capabilities;
        this.jwtToken     = jwtToken;
        this.reconnectMs  = reconnectMs;
    }

    @Override
    public void run() {
        // Memory persists across reconnections (same as Erlang gen_server state).
        Map<String, Object> memory = new HashMap<>();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                connectOnce(memory);
                log.info("[em_filter] " + agentName + " disconnected — reconnecting");
            } catch (Exception e) {
                log.warning("[em_filter] " + agentName + " error: " + e.getMessage());
            }
            try {
                Thread.sleep(reconnectMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void connectOnce(Map<String, Object> memory) throws Exception {
        String scheme = node.tls ? "wss" : "ws";
        String path   = (jwtToken != null) ? "/ws?token=" + jwtToken : "/ws";
        URI    uri    = URI.create(scheme + "://" + node.host + ":" + node.port + path);

        log.info("[em_filter] " + agentName + " connecting to " + uri);

        CountDownLatch closeLatch = new CountDownLatch(1);

        // Use an array to allow mutation from the anonymous inner class.
        @SuppressWarnings("unchecked")
        Map<String, Object>[] memRef = new Map[]{memory};

        WebSocketClient ws = new WebSocketClient(uri) {

            @Override
            public void onOpen(ServerHandshake hs) {
                try {
                    // Step 1 — register
                    ObjectNode reg = MAPPER.createObjectNode();
                    reg.put("action", "register");
                    reg.put("name", agentName);
                    send(MAPPER.writeValueAsString(reg));

                    // Step 2 — agent_hello (always sent, even with empty list)
                    ObjectNode hello = MAPPER.createObjectNode();
                    hello.put("action", "agent_hello");
                    hello.putPOJO("capabilities", capabilities);
                    send(MAPPER.writeValueAsString(hello));

                    log.info("[em_filter] " + agentName +
                             " registered — entering message loop");
                } catch (Exception e) {
                    log.warning("[em_filter] " + agentName +
                                " handshake error: " + e.getMessage());
                    close();
                }
            }

            @Override
            public void onMessage(String message) {
                try {
                    JsonNode msg = MAPPER.readTree(message);
                    if (!"query".equals(msg.path("action").asText())) return;

                    String queryId = msg.path("id").asText(null);
                    if (queryId == null) {
                        log.warning("[em_filter] " + agentName +
                                    " query missing 'id', skipping");
                        return;
                    }
                    String body = msg.path("body").asText("").trim();
                    log.info("[em_filter] " + agentName + " query " + queryId + ": " + body);

                    Object resultData;
                    try {
                        FilterResult fr = filter.handle(body, memRef[0]);
                        memRef[0] = fr.memory;
                        resultData = fr.data;
                    } catch (Exception e) {
                        log.severe("[em_filter] " + agentName +
                                   " handler error: " + e.getMessage());
                        resultData = null;
                    }

                    ObjectNode resp = MAPPER.createObjectNode();
                    resp.put("action", "result");
                    resp.put("id", queryId);
                    resp.putPOJO("data", resultData);
                    send(MAPPER.writeValueAsString(resp));

                } catch (Exception e) {
                    log.warning("[em_filter] " + agentName +
                                " message error: " + e.getMessage());
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                log.info("[em_filter] " + agentName + " closed (code=" + code + ")");
                closeLatch.countDown();
            }

            @Override
            public void onError(Exception ex) {
                log.warning("[em_filter] " + agentName + " WS error: " + ex.getMessage());
                closeLatch.countDown();
            }
        };

        ws.connectBlocking();
        closeLatch.await();   // block until closed or error
        // Copy updated memory back to the caller's map reference.
        memory.clear();
        memory.putAll(memRef[0]);
    }
}

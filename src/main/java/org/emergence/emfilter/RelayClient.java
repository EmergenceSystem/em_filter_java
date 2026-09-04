package org.emergence.emfilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Model B (WS relay) transport: holds an outbound WebSocket to a disco so the
 * filter never needs inbound reachability.
 *
 * <p>Handshake: {@code hello} (self-signed identity + capabilities) →
 * {@code hello_ok}/{@code error}. Then a {@code query}/{@code result} loop: the
 * filter signs each result itself, so the disco (a dumb relay) cannot forge a
 * result for this id.
 */
public final class RelayClient {

    private static final Logger log = Logger.getLogger(RelayClient.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long HELLO_TIMEOUT_S = 10;

    private final Identity identity;
    private final Filter filter;
    private final URI discoUrl;
    private final long reconnectMs;
    private final AtomicReference<Map<String, Object>> memory = new AtomicReference<>(new ConcurrentHashMap<>());

    private volatile boolean running;

    public RelayClient(Identity identity, Filter filter, URI discoUrl, long reconnectMs) {
        this.identity = identity;
        this.filter = filter;
        this.discoUrl = discoUrl;
        this.reconnectMs = reconnectMs;
    }

    /** Connect, hello, and process queries forever, reconnecting after {@code reconnectMs} on any drop. */
    public void runForever() {
        running = true;
        while (running) {
            try {
                session();
            } catch (Exception e) {
                log.log(Level.WARNING, "relay session error", e);
            }
            if (!running) break;
            try {
                Thread.sleep(reconnectMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void stop() {
        running = false;
    }

    /** Run exactly one connect/hello/query-loop session; returns once the socket closes. */
    void session() throws Exception {
        CountDownLatch closeLatch = new CountDownLatch(1);
        CountDownLatch helloLatch = new CountDownLatch(1);
        AtomicBoolean helloOk = new AtomicBoolean(false);

        WebSocketClient ws = new WebSocketClient(discoUrl) {

            @Override
            public void onOpen(ServerHandshake handshake) {
                try {
                    send(MAPPER.writeValueAsString(identity.helloPayload()));
                } catch (Exception e) {
                    log.log(Level.WARNING, "failed to send hello", e);
                    close();
                }
            }

            @Override
            public void onMessage(String message) {
                try {
                    JsonNode msg = MAPPER.readTree(message);
                    String action = msg.path("action").asText();

                    if ("hello_ok".equals(action)) {
                        helloOk.set(true);
                        helloLatch.countDown();
                        return;
                    }
                    if ("error".equals(action)) {
                        log.warning("relay hello rejected: " + message);
                        helloLatch.countDown();
                        close();
                        return;
                    }
                    if (!"query".equals(action)) {
                        return;
                    }

                    String qid = msg.path("id").asText(null);
                    String body = msg.path("body").asText("");

                    Object data;
                    try {
                        FilterResult fr = filter.handle(body, memory.get());
                        memory.set(fr.memory != null ? fr.memory : new ConcurrentHashMap<>());
                        data = fr.data;
                    } catch (Exception e) {
                        log.log(Level.WARNING, "handler error", e);
                        data = List.of();
                    }

                    JsonNode items = MAPPER.valueToTree(data);
                    Crypto.SignedResponse sr = identity.signResults(items);

                    ObjectNode resp = MAPPER.createObjectNode();
                    resp.put("action", "result");
                    resp.put("id", qid);
                    resp.set("results", items);
                    resp.put("signer_id", sr.signerId());
                    resp.put("signature", sr.signature());
                    send(MAPPER.writeValueAsString(resp));
                } catch (Exception e) {
                    log.log(Level.WARNING, "message handling error", e);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                helloLatch.countDown();
                closeLatch.countDown();
            }

            @Override
            public void onError(Exception ex) {
                log.log(Level.WARNING, "relay WS error", ex);
                helloLatch.countDown();
                closeLatch.countDown();
            }
        };

        ws.connectBlocking();
        boolean acked = helloLatch.await(HELLO_TIMEOUT_S, TimeUnit.SECONDS);
        if (!acked || !helloOk.get()) {
            ws.close();
            throw new RuntimeException("relay hello rejected or timed out");
        }
        closeLatch.await();
    }
}

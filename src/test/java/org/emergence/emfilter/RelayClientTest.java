package org.emergence.emfilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Stub disco: acks hello, sends one query, captures the signed result. */
    private static final class StubDisco extends WebSocketServer {
        final CompletableFuture<JsonNode> result = new CompletableFuture<>();

        StubDisco(int port) {
            super(new InetSocketAddress("127.0.0.1", port));
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {}

        @Override
        public void onMessage(WebSocket conn, String message) {
            try {
                JsonNode msg = MAPPER.readTree(message);
                String action = msg.path("action").asText();
                if ("hello".equals(action)) {
                    conn.send("{\"action\":\"hello_ok\",\"id\":\"stub\"}");
                    conn.send("{\"action\":\"query\",\"id\":\"q1\",\"body\":\"hi\"}");
                } else if ("result".equals(action)) {
                    result.complete(msg);
                    conn.close();
                }
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {}

        @Override
        public void onError(WebSocket conn, Exception ex) {
            result.completeExceptionally(ex);
        }

        @Override
        public void onStart() {}
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void helloQueryResultRoundTrip(@TempDir Path tmp) throws Exception {
        int port = freePort();
        StubDisco stub = new StubDisco(port);
        stub.start();
        try {
            Identity identity = new Identity("relay", tmp, List.of("search"));
            Filter handler = new Filter() {
                @Override
                public FilterResult handle(String body, Map<String, Object> memory) {
                    return new FilterResult(List.of(Map.of("url", "https://x/1", "title", "T: " + body)), memory);
                }
            };
            RelayClient client = new RelayClient(identity, handler, URI.create("ws://127.0.0.1:" + port), 5000);

            Thread t = new Thread(() -> {
                try {
                    client.session();
                } catch (Exception ignored) {
                    // expected once the stub closes the socket after the result arrives
                }
            });
            t.setDaemon(true);
            t.start();

            JsonNode result = stub.result.get(5, TimeUnit.SECONDS);
            assertEquals("result", result.get("action").asText());
            assertEquals("q1", result.get("id").asText());
            assertEquals(Base64.getEncoder().encodeToString(identity.id()), result.get("signer_id").asText());

            byte[] sig = Base64.getDecoder().decode(result.get("signature").asText());
            byte[] canon = Crypto.canonicalResponse(result.get("results"));
            assertTrue(Crypto.verify(canon, sig, identity.pub()));

            t.join(5000);
        } finally {
            stub.stop(1000);
        }
    }
}

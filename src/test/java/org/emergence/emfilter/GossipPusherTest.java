package org.emergence.emfilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GossipPusherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer stub;

    @AfterEach
    void tearDown() {
        if (stub != null) stub.stop(0);
    }

    @Test
    void pushOnceSendsVerifiableSelfPayload(@TempDir Path tmp) throws Exception {
        CompletableFuture<byte[]> captured = new CompletableFuture<>();
        CompletableFuture<com.sun.net.httpserver.Headers> capturedHeaders = new CompletableFuture<>();
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/pop/gossip", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            captured.complete(body);
            capturedHeaders.complete(ex.getRequestHeaders());
            byte[] resp = "{}".getBytes();
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        stub.start();

        Identity identity = new Identity("pusher", tmp, List.of("search"));
        GossipPusher pusher = new GossipPusher(identity,
                List.of(new DiscoNode("127.0.0.1", stub.getAddress().getPort(), false)),
                "9.9.9.9", 9600, 5000);

        pusher.pushOnce();

        byte[] raw = captured.get(2, TimeUnit.SECONDS);
        JsonNode json = MAPPER.readTree(raw);

        assertEquals("filter", json.get("role").asText());
        assertEquals(9600, json.get("query_port").asInt());
        assertEquals("9.9.9.9", json.get("host").asText());
        assertTrue(json.has("pubkey"));
        assertTrue(json.has("sig"));
        assertEquals(List.of("search"), MAPPER.convertValue(json.get("capabilities"), List.class));

        com.sun.net.httpserver.Headers h = capturedHeaders.get(2, TimeUnit.SECONDS);
        byte[] id = java.util.Base64.getDecoder().decode(h.getFirst("x-pop-id"));
        assertEquals(java.util.Base64.getEncoder().encodeToString(identity.id()), h.getFirst("x-pop-id"));
        long ts = Long.parseLong(h.getFirst("x-pop-ts"));
        byte[] sig = java.util.Base64.getDecoder().decode(h.getFirst("x-pop-sig"));
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw);
        assertTrue(Crypto.verify(Crypto.canonicalGossipAuth(id, ts, digest), sig, identity.pub()));
    }
}

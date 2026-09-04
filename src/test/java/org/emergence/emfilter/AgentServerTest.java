package org.emergence.emfilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private Identity identity;
    private AgentServer server;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        identity = new Identity("srv", tmp, List.of("search"));
        Filter handler = new Filter() {
            @Override
            public FilterResult handle(String body, Map<String, Object> memory) {
                if ("boom".equals(body)) throw new RuntimeException("handler exploded");
                List<Map<String, Object>> result = List.of(Map.of(
                        "url", "https://x/1", "title", "T", "resume", "R"));
                return new FilterResult(result, memory);
            }
        };
        server = new AgentServer(identity, handler, "127.0.0.1", 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void agentQueryReturnsVerifiableSignedResults() throws Exception {
        HttpResponse<String> resp = post("/agent/query", "{\"query\":\"hi\"}");
        assertEquals(200, resp.statusCode());
        JsonNode json = MAPPER.readTree(resp.body());

        assertEquals(Base64.getEncoder().encodeToString(identity.id()), json.get("signer_id").asText());
        byte[] sig = Base64.getDecoder().decode(json.get("signature").asText());
        byte[] canon = Crypto.canonicalResponse(json.get("results"));
        assertTrue(Crypto.verify(canon, sig, identity.pub()));
    }

    @Test
    void malformedQueryReturns400() throws Exception {
        HttpResponse<String> resp = post("/agent/query", "{}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void handlerErrorReturns500() throws Exception {
        HttpResponse<String> resp = post("/agent/query", "{\"query\":\"boom\"}");
        assertEquals(500, resp.statusCode());
    }

    @Test
    void gossipReturnsOwnSelfPayload() throws Exception {
        HttpResponse<String> resp = post("/pop/gossip", "{\"id\":\"abc\",\"name\":\"peer\"}");
        assertEquals(200, resp.statusCode());
        JsonNode json = MAPPER.readTree(resp.body());
        assertEquals("filter", json.get("role").asText());
        assertEquals(Base64.getEncoder().encodeToString(identity.id()), json.get("id").asText());
    }

    @Test
    void healthReportsOk() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.port() + "/health"))
                .GET().build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertEquals("ok", resp.body());
    }
}

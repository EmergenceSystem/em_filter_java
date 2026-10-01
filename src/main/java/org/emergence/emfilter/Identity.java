package org.emergence.emfilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds this agent's ed25519 keypair, name and capabilities, and builds the
 * self-signed payloads shared by both transports (Model A gossip, Model B hello),
 * plus the response-signing helper both use for {@code /agent/query} / relay results.
 */
public final class Identity {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final List<String> capabilities;
    private final byte[] pub;
    private final byte[] seed;
    private final byte[] id;

    public Identity(String name, Path keyDir, List<String> capabilities) throws IOException, NoSuchAlgorithmException {
        this.name = name;
        this.capabilities = capabilities;
        Crypto.KeyPair kp = Crypto.loadOrCreate(keyDir);
        this.pub = kp.pub();
        this.seed = kp.seed();
        this.id = Crypto.idOf(pub);
    }

    public String name() { return name; }
    public byte[] pub() { return pub; }
    public byte[] id() { return id; }
    public List<String> capabilities() { return capabilities; }

    private String selfSigB64() {
        byte[] sig = Crypto.sign(Crypto.canonicalIdentity(id, name), seed);
        return Base64.getEncoder().encodeToString(sig);
    }

    /** {@code {"action":"hello", "name", "pubkey", "sig", "capabilities"}} — Model B handshake. */
    public JsonNode helloPayload() {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("action", "hello");
        n.put("name", name);
        n.put("pubkey", Base64.getEncoder().encodeToString(pub));
        n.put("sig", selfSigB64());
        n.putPOJO("capabilities", capabilities);
        return n;
    }

    /** {@code {"id","name","host","query_port","pubkey","sig","capabilities","role":"filter"}} — Model A gossip push. */
    public JsonNode gossipPayload(String host, int queryPort) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("id", Base64.getEncoder().encodeToString(id));
        n.put("name", name);
        n.put("host", host);
        n.put("query_port", queryPort);
        n.put("pubkey", Base64.getEncoder().encodeToString(pub));
        n.put("sig", selfSigB64());
        n.putPOJO("capabilities", capabilities);
        n.put("role", "filter");
        return n;
    }

    /** Sign a results list for {@code /agent/query} or the relay {@code result} frame. */
    public Crypto.SignedResponse signResults(JsonNode items) throws NoSuchAlgorithmException {
        return Crypto.signResponse(items, pub, seed);
    }

    /** A v2-signed response: the timestamp that was signed, plus {@code signer_id} and {@code signature}. */
    public record SignedResultsV2(long ts, String signerId, String signature) {}

    /**
     * Sign a results list with the v2 scheme, binding it to {@code query} and a fresh
     * timestamp (epoch milliseconds). The caller must emit the returned {@code ts} alongside
     * {@code signer_id} and {@code signature}.
     */
    public SignedResultsV2 signResultsV2(String query, JsonNode items) throws NoSuchAlgorithmException {
        long ts = System.currentTimeMillis();
        Crypto.SignedResponse sr = Crypto.signResponseV2(query, ts, items, pub, seed);
        return new SignedResultsV2(ts, sr.signerId(), sr.signature());
    }

    /**
     * Gossip-auth headers ({@code x-pop-id}, {@code x-pop-ts}, {@code x-pop-sig}) for an outbound
     * {@code POST /pop/gossip} whose exact body bytes are {@code body}.
     */
    public Map<String, String> gossipHeaders(byte[] body) throws NoSuchAlgorithmException {
        long ts = System.currentTimeMillis();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("x-pop-id", Base64.getEncoder().encodeToString(id));
        h.put("x-pop-ts", Long.toString(ts));
        h.put("x-pop-sig", Crypto.signGossip(id, ts, body, seed));
        return h;
    }
}

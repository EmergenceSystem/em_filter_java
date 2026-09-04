package org.emergence.emfilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

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
}

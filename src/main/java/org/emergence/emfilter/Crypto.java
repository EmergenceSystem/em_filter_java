package org.emergence.emfilter;

import com.fasterxml.jackson.databind.JsonNode;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Ed25519 identity/crypto primitives, byte-identical to the Erlang
 * {@code em_pop_crypto} reference. Every value here MUST reproduce
 * {@code fixtures/crypto_vectors.json} exactly — see {@code CryptoVectorsTest}.
 */
public final class Crypto {

    /** Key file name, matching the Erlang {@code load_or_create/1} layout. */
    public static final String KEY_FILE_NAME = "node_ed25519.key";

    private Crypto() {}

    /** Peer id = SHA-256(pubkey)[0:16]. */
    public static byte[] idOf(byte[] pub) throws NoSuchAlgorithmException {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(pub);
        return Arrays.copyOfRange(h, 0, 16);
    }

    /** {@code id ‖ 0x00 ‖ name_utf8} — the message self-signed at identity time. */
    public static byte[] canonicalIdentity(byte[] id, String name) {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(id.length + 1 + n.length);
        bb.put(id).put((byte) 0).put(n);
        return bb.array();
    }

    /**
     * Canonical byte form of a results list, one line per item:
     * {@code url \0 title \0 resume \n}, UTF-8, no escaping.
     *
     * <p>For each object item, {@code properties} (if itself an object) is used
     * in place of the item; {@code title} falls back to {@code label}, and
     * {@code resume} falls back to {@code value} then {@code description}.
     * A non-object item, or one with no matching fields, yields an empty line
     * ({@code \0\0\n}). A non-array {@code items} value yields empty bytes.
     */
    public static byte[] canonicalResponse(JsonNode items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (items == null || !items.isArray()) {
            return new byte[0];
        }
        for (JsonNode item : items) {
            JsonNode p = null;
            if (item != null && item.isObject()) {
                JsonNode props = item.get("properties");
                p = (props != null && props.isObject()) ? props : item;
            }
            writeField(out, pick(p, "url"));
            out.write(0);
            writeField(out, pick(p, "title", "label"));
            out.write(0);
            writeField(out, pick(p, "resume", "value", "description"));
            out.write('\n');
        }
        return out.toByteArray();
    }

    private static void writeField(ByteArrayOutputStream out, byte[] field) {
        out.write(field, 0, field.length);
    }

    private static byte[] pick(JsonNode obj, String... keys) {
        if (obj == null) return new byte[0];
        for (String k : keys) {
            JsonNode v = obj.get(k);
            if (v != null && v.isTextual()) {
                return v.asText().getBytes(StandardCharsets.UTF_8);
            }
        }
        return new byte[0];
    }

    /** Sign {@code msg} with the 32-byte ed25519 seed. Returns a 64-byte signature. */
    public static byte[] sign(byte[] msg, byte[] seed) {
        Ed25519PrivateKeyParameters priv = new Ed25519PrivateKeyParameters(seed, 0);
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, priv);
        signer.update(msg, 0, msg.length);
        return signer.generateSignature();
    }

    /** Verify a 64-byte ed25519 {@code sig} over {@code msg} against a 32-byte pubkey. */
    public static boolean verify(byte[] msg, byte[] sig, byte[] pub) {
        try {
            Ed25519PublicKeyParameters pubParams = new Ed25519PublicKeyParameters(pub, 0);
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(false, pubParams);
            signer.update(msg, 0, msg.length);
            return signer.verifySignature(sig);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** {@code signer_id} (base64 id) and {@code signature} (base64 ed25519 sig) for a results list. */
    public record SignedResponse(String signerId, String signature) {}

    public static SignedResponse signResponse(JsonNode items, byte[] pub, byte[] seed) throws NoSuchAlgorithmException {
        byte[] sig = sign(canonicalResponse(items), seed);
        String signerId = Base64.getEncoder().encodeToString(idOf(pub));
        String signature = Base64.getEncoder().encodeToString(sig);
        return new SignedResponse(signerId, signature);
    }

    /**
     * v2 canonical response, binding the response to its query and timestamp:
     * {@code utf8(query) ‖ 0x00 ‖ ascii(decimal(ts)) ‖ 0x00 ‖ canonical_response(items)}.
     */
    public static byte[] canonicalResponseV2(String query, long ts, JsonNode items) {
        byte[] q = query.getBytes(StandardCharsets.UTF_8);
        byte[] t = Long.toString(ts).getBytes(StandardCharsets.US_ASCII);
        byte[] body = canonicalResponse(items);
        ByteBuffer bb = ByteBuffer.allocate(q.length + 1 + t.length + 1 + body.length);
        bb.put(q).put((byte) 0).put(t).put((byte) 0).put(body);
        return bb.array();
    }

    /** v2 counterpart of {@link #signResponse}: signs {@link #canonicalResponseV2}. */
    public static SignedResponse signResponseV2(String query, long ts, JsonNode items, byte[] pub, byte[] seed)
            throws NoSuchAlgorithmException {
        byte[] sig = sign(canonicalResponseV2(query, ts, items), seed);
        String signerId = Base64.getEncoder().encodeToString(idOf(pub));
        String signature = Base64.getEncoder().encodeToString(sig);
        return new SignedResponse(signerId, signature);
    }

    /**
     * Canonical gossip-auth message:
     * {@code id(16) ‖ 0x00 ‖ ascii(decimal(ts)) ‖ 0x00 ‖ sha256(body)}.
     *
     * @param bodySha256 the 32-byte SHA-256 digest of the request body
     */
    public static byte[] canonicalGossipAuth(byte[] id, long ts, byte[] bodySha256) {
        byte[] t = Long.toString(ts).getBytes(StandardCharsets.US_ASCII);
        ByteBuffer bb = ByteBuffer.allocate(id.length + 1 + t.length + 1 + bodySha256.length);
        bb.put(id).put((byte) 0).put(t).put((byte) 0).put(bodySha256);
        return bb.array();
    }

    /** Base64 ed25519 signature over {@code canonicalGossipAuth(id, ts, sha256(body))}. */
    public static String signGossip(byte[] id, long ts, byte[] body, byte[] seed) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
        byte[] sig = sign(canonicalGossipAuth(id, ts, digest), seed);
        return Base64.getEncoder().encodeToString(sig);
    }

    /** {@code pubkey(32)} and {@code seed(32)} loaded from — or created and persisted to — {@code keyDir}. */
    public record KeyPair(byte[] pub, byte[] seed) {}

    /**
     * Load {@code <keyDir>/node_ed25519.key} (raw {@code pub(32) ‖ seed(32)}), or create it
     * on first run — same file layout as the Erlang {@code load_or_create/1}, portable
     * across implementations.
     */
    public static KeyPair loadOrCreate(Path keyDir) throws IOException {
        Path file = keyDir.resolve(KEY_FILE_NAME);
        if (Files.exists(file)) {
            byte[] raw = Files.readAllBytes(file);
            byte[] pub = Arrays.copyOfRange(raw, 0, 32);
            byte[] seed = Arrays.copyOfRange(raw, 32, 64);
            return new KeyPair(pub, seed);
        }
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        Ed25519PrivateKeyParameters priv = new Ed25519PrivateKeyParameters(seed, 0);
        byte[] pub = priv.generatePublicKey().getEncoded();

        Files.createDirectories(keyDir);
        byte[] raw = new byte[64];
        System.arraycopy(pub, 0, raw, 0, 32);
        System.arraycopy(seed, 0, raw, 32, 32);
        Files.write(file, raw);
        return new KeyPair(pub, seed);
    }
}

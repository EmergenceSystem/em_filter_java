package org.emergence.emfilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-language parity test for v2 response signing and gossip-auth, against the
 * shared {@code fixtures/crypto_vectors.json} (read from the repo root, the Maven
 * working directory).
 */
class CryptoV2GossipTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();

    private static JsonNode loadFixture() throws Exception {
        return MAPPER.readTree(Files.readString(Path.of("fixtures", "crypto_vectors.json")));
    }

    @Test
    void canonicalResponseV2MatchesFixture() throws Exception {
        JsonNode fx = loadFixture();
        byte[] canon = Crypto.canonicalResponseV2(
                fx.get("v2_query").asText(), fx.get("v2_ts").asLong(), fx.get("items"));
        assertArrayEquals(HEX.parseHex(fx.get("canonical_response_v2_hex").asText()), canon);
    }

    @Test
    void responseV2SignatureMatchesFixtureAndVerifies() throws Exception {
        JsonNode fx = loadFixture();
        byte[] pub = HEX.parseHex(fx.get("pubkey_hex").asText());
        byte[] seed = HEX.parseHex(fx.get("privkey_hex").asText());
        byte[] canon = Crypto.canonicalResponseV2(
                fx.get("v2_query").asText(), fx.get("v2_ts").asLong(), fx.get("items"));
        byte[] sig = Crypto.sign(canon, seed);
        assertEquals(fx.get("response_v2_signature_b64").asText(), Base64.getEncoder().encodeToString(sig));
        assertTrue(Crypto.verify(canon, sig, pub));
    }

    @Test
    void signResponseV2HelperMatchesFixture() throws Exception {
        JsonNode fx = loadFixture();
        byte[] pub = HEX.parseHex(fx.get("pubkey_hex").asText());
        byte[] seed = HEX.parseHex(fx.get("privkey_hex").asText());
        Crypto.SignedResponse sr = Crypto.signResponseV2(
                fx.get("v2_query").asText(), fx.get("v2_ts").asLong(), fx.get("items"), pub, seed);
        assertEquals(fx.get("signer_id_b64").asText(), sr.signerId());
        assertEquals(fx.get("response_v2_signature_b64").asText(), sr.signature());
    }

    @Test
    void canonicalGossipAuthMatchesFixture() throws Exception {
        JsonNode fx = loadFixture();
        byte[] id = HEX.parseHex(fx.get("id_hex").asText());
        byte[] body = fx.get("gossip_body_utf8").asText().getBytes(StandardCharsets.UTF_8);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
        assertArrayEquals(HEX.parseHex(fx.get("gossip_body_sha256_hex").asText()), digest);

        byte[] canon = Crypto.canonicalGossipAuth(id, fx.get("gossip_ts").asLong(), digest);
        assertArrayEquals(HEX.parseHex(fx.get("canonical_gossip_auth_hex").asText()), canon);
    }

    @Test
    void signGossipMatchesFixtureAndVerifies() throws Exception {
        JsonNode fx = loadFixture();
        byte[] pub = HEX.parseHex(fx.get("pubkey_hex").asText());
        byte[] seed = HEX.parseHex(fx.get("privkey_hex").asText());
        byte[] id = HEX.parseHex(fx.get("id_hex").asText());
        byte[] body = fx.get("gossip_body_utf8").asText().getBytes(StandardCharsets.UTF_8);
        long ts = fx.get("gossip_ts").asLong();

        String sigB64 = Crypto.signGossip(id, ts, body, seed);
        assertEquals(fx.get("gossip_signature_b64").asText(), sigB64);

        byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
        byte[] canon = Crypto.canonicalGossipAuth(id, ts, digest);
        assertTrue(Crypto.verify(canon, Base64.getDecoder().decode(sigB64), pub));
    }
}

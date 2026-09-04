package org.emergence.emfilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-language crypto parity test: reproduces {@code fixtures/crypto_vectors.json},
 * generated from the Erlang {@code em_pop_crypto} reference, byte-for-byte.
 */
class CryptoVectorsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();

    private static JsonNode loadFixture() throws Exception {
        try (InputStream in = CryptoVectorsTest.class.getResourceAsStream("/fixtures/crypto_vectors.json")) {
            return MAPPER.readTree(in);
        }
    }

    @Test
    void idOfMatchesFixture() throws Exception {
        JsonNode fx = loadFixture();
        byte[] pub = HEX.parseHex(fx.get("pubkey_hex").asText());
        byte[] id = Crypto.idOf(pub);
        assertArrayEquals(HEX.parseHex(fx.get("id_hex").asText()), id);
        assertEquals(fx.get("signer_id_b64").asText(), Base64.getEncoder().encodeToString(id));
    }

    @Test
    void canonicalIdentityMatchesFixture() throws Exception {
        JsonNode fx = loadFixture();
        byte[] id = HEX.parseHex(fx.get("id_hex").asText());
        byte[] canon = Crypto.canonicalIdentity(id, fx.get("name").asText());
        assertArrayEquals(HEX.parseHex(fx.get("canonical_identity_hex").asText()), canon);
    }

    @Test
    void selfSigMatchesFixture() throws Exception {
        JsonNode fx = loadFixture();
        byte[] id = HEX.parseHex(fx.get("id_hex").asText());
        byte[] seed = HEX.parseHex(fx.get("privkey_hex").asText());
        byte[] canon = Crypto.canonicalIdentity(id, fx.get("name").asText());
        byte[] sig = Crypto.sign(canon, seed);
        assertEquals(fx.get("selfsig_b64").asText(), Base64.getEncoder().encodeToString(sig));
    }

    @Test
    void canonicalResponseAndSignatureMatchFixture() throws Exception {
        JsonNode fx = loadFixture();
        byte[] pub = HEX.parseHex(fx.get("pubkey_hex").asText());
        byte[] seed = HEX.parseHex(fx.get("privkey_hex").asText());

        byte[] canon = Crypto.canonicalResponse(fx.get("items"));
        assertArrayEquals(HEX.parseHex(fx.get("canonical_response_hex").asText()), canon);

        byte[] sig = Crypto.sign(canon, seed);
        assertEquals(fx.get("response_signature_b64").asText(), Base64.getEncoder().encodeToString(sig));
        assertTrue(Crypto.verify(canon, sig, pub));
    }

    @Test
    void signResponseHelperMatchesFixture() throws Exception {
        JsonNode fx = loadFixture();
        byte[] pub = HEX.parseHex(fx.get("pubkey_hex").asText());
        byte[] seed = HEX.parseHex(fx.get("privkey_hex").asText());

        Crypto.SignedResponse sr = Crypto.signResponse(fx.get("items"), pub, seed);
        assertEquals(fx.get("signer_id_b64").asText(), sr.signerId());
        assertEquals(fx.get("response_signature_b64").asText(), sr.signature());
    }

    @Test
    void roundTripReSignAndVerify() throws Exception {
        JsonNode fx = loadFixture();
        byte[] pub = HEX.parseHex(fx.get("pubkey_hex").asText());
        byte[] seed = HEX.parseHex(fx.get("privkey_hex").asText());
        byte[] msg = "round-trip".getBytes(StandardCharsets.UTF_8);
        byte[] sig = Crypto.sign(msg, seed);
        assertTrue(Crypto.verify(msg, sig, pub));
        assertTrue(!Crypto.verify("tampered".getBytes(StandardCharsets.UTF_8), sig, pub));
    }
}

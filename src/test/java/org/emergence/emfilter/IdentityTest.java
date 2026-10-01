package org.emergence.emfilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void helloPayloadIsSelfConsistentAndVerifiable(@TempDir Path tmp) throws Exception {
        Identity ident = new Identity("t", tmp, List.of("search"));
        JsonNode h = ident.helloPayload();

        assertEquals("hello", h.get("action").asText());
        assertEquals("t", h.get("name").asText());
        assertEquals(Base64.getEncoder().encodeToString(ident.pub()), h.get("pubkey").asText());

        byte[] sig = Base64.getDecoder().decode(h.get("sig").asText());
        assertTrue(Crypto.verify(Crypto.canonicalIdentity(ident.id(), "t"), sig, ident.pub()));
    }

    @Test
    void gossipPayloadCarriesHostAndPort(@TempDir Path tmp) throws Exception {
        Identity ident = new Identity("t", tmp, List.of("search"));
        JsonNode g = ident.gossipPayload("1.2.3.4", 9600);

        assertEquals(9600, g.get("query_port").asInt());
        assertEquals("filter", g.get("role").asText());
        assertEquals("1.2.3.4", g.get("host").asText());
        assertEquals(Base64.getEncoder().encodeToString(ident.id()), g.get("id").asText());
    }

    @Test
    void signResultsMatchesFixture() throws Exception {
        JsonNode fx;
        try (var in = IdentityTest.class.getResourceAsStream("/fixtures/crypto_vectors.json")) {
            fx = MAPPER.readTree(in);
        }
        // Build an Identity around the fixture keypair by writing the key file directly.
        Path tmp = java.nio.file.Files.createTempDirectory("em-filter-identity-test");
        byte[] pub = java.util.HexFormat.of().parseHex(fx.get("pubkey_hex").asText());
        byte[] seed = java.util.HexFormat.of().parseHex(fx.get("privkey_hex").asText());
        byte[] raw = new byte[64];
        System.arraycopy(pub, 0, raw, 0, 32);
        System.arraycopy(seed, 0, raw, 32, 32);
        java.nio.file.Files.write(tmp.resolve(Crypto.KEY_FILE_NAME), raw);

        Identity ident = new Identity(fx.get("name").asText(), tmp, List.of("search"));
        Crypto.SignedResponse sr = ident.signResults(fx.get("items"));
        assertEquals(fx.get("signer_id_b64").asText(), sr.signerId());
        assertEquals(fx.get("response_signature_b64").asText(), sr.signature());
    }

    @Test
    void signResultsV2Verifies(@TempDir Path tmp) throws Exception {
        Identity ident = new Identity("t", tmp, List.of("search"));
        JsonNode items = MAPPER.readTree("[{\"url\":\"https://x/1\",\"title\":\"T\"}]");
        Identity.SignedResultsV2 sr = ident.signResultsV2("hello", items);

        assertEquals(Base64.getEncoder().encodeToString(ident.id()), sr.signerId());
        assertTrue(sr.ts() > 0);
        byte[] sig = Base64.getDecoder().decode(sr.signature());
        assertTrue(Crypto.verify(Crypto.canonicalResponseV2("hello", sr.ts(), items), sig, ident.pub()));
        assertTrue(!Crypto.verify(Crypto.canonicalResponseV2("other", sr.ts(), items), sig, ident.pub()));
    }

    @Test
    void gossipHeadersVerify(@TempDir Path tmp) throws Exception {
        Identity ident = new Identity("t", tmp, List.of("search"));
        byte[] body = "{\"a\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.util.Map<String, String> h = ident.gossipHeaders(body);

        assertEquals(Base64.getEncoder().encodeToString(ident.id()), h.get("x-pop-id"));
        long ts = Long.parseLong(h.get("x-pop-ts"));
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(body);
        byte[] sig = Base64.getDecoder().decode(h.get("x-pop-sig"));
        assertTrue(Crypto.verify(Crypto.canonicalGossipAuth(ident.id(), ts, digest), sig, ident.pub()));
    }
}

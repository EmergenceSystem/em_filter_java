package org.emergence.emfilter;

import org.emergence.emfilter.html.HtmlUtils;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HtmlUtilsTest {

    @Test
    void stripScriptsRemovesScriptBlock() {
        String html = "<p>Hello</p><script>alert(1)</script><p>World</p>";
        String result = HtmlUtils.stripScripts(html);
        assertFalse(result.contains("script"));
        assertTrue(result.contains("Hello"));
    }

    @Test
    void stripScriptsMultiline() {
        String html = "<p>A</p><script type=\"text/javascript\">\nvar x=1;\n</script><p>B</p>";
        String result = HtmlUtils.stripScripts(html);
        assertFalse(result.contains("var x"));
        assertTrue(result.contains("A") && result.contains("B"));
    }

    @Test
    void getTextStripsAllTags() {
        assertEquals("Hello world", HtmlUtils.getText("<p>Hello <b>world</b></p>"));
    }

    @Test
    void getTextNoTags() {
        assertEquals("plain text", HtmlUtils.getText("plain text"));
    }

    @Test
    void extractElementsByTag() {
        List<String> elems = HtmlUtils.extractElements("<div>A</div><div>B</div>", "div");
        assertEquals(2, elems.size());
        assertTrue(elems.contains("A"));
        assertTrue(elems.contains("B"));
    }

    @Test
    void extractElementsByClass() {
        List<String> elems = HtmlUtils.extractElements(
            "<li class=\"b_algo\">item</li><li>other</li>", "li.b_algo");
        assertEquals(1, elems.size());
        assertTrue(elems.get(0).contains("item"));
    }

    @Test
    void extractAttributeFound() {
        assertEquals("/page", HtmlUtils.extractAttribute("<a href=\"/page\">link</a>", "href"));
    }

    @Test
    void extractAttributeNotFound() {
        assertNull(HtmlUtils.extractAttribute("<a>link</a>", "href"));
    }

    @Test
    void decodeNumericEntity() {
        assertEquals("é", HtmlUtils.decodeHtmlEntities("&#233;"));
    }

    @Test
    void decodeHexEntity() {
        assertEquals("é", HtmlUtils.decodeHtmlEntities("&#xE9;"));
    }

    @Test
    void decodeNamedEntity() {
        assertEquals("é", HtmlUtils.decodeHtmlEntities("&eacute;"));
        assertEquals("&", HtmlUtils.decodeHtmlEntities("&amp;"));
        assertEquals("<", HtmlUtils.decodeHtmlEntities("&lt;"));
    }

    @Test
    void decodeCombined() {
        assertEquals("café & croissant",
            HtmlUtils.decodeHtmlEntities("caf&eacute; &amp; croissant"));
    }

    @Test
    void shouldSkipLinkNotHttp() {
        assertTrue(HtmlUtils.shouldSkipLink("ftp://example.com", List.of()));
    }

    @Test
    void shouldSkipLinkExcluded() {
        assertTrue(HtmlUtils.shouldSkipLink("https://ads.example.com/x",
            List.of("ads.example.com")));
    }

    @Test
    void shouldSkipLinkOk() {
        assertFalse(HtmlUtils.shouldSkipLink("https://example.com", List.of("ads.com")));
    }
}

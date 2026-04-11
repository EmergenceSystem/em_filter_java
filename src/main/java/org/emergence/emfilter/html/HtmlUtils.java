package org.emergence.emfilter.html;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTML utilities mirroring the Erlang em_filter HTML functions. */
public final class HtmlUtils {

    private HtmlUtils() {}

    private static final Pattern PAT_SCRIPT =
        Pattern.compile("<script[^>]*>.*?</script>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_TAGS   = Pattern.compile("<[^>]+>");
    private static final Pattern PAT_DEC    = Pattern.compile("&#([0-9]+);");
    private static final Pattern PAT_HEX    = Pattern.compile("&#x([0-9A-Fa-f]+);");
    private static final Pattern PAT_NAM    = Pattern.compile("&([a-zA-Z]+);");

    private static final Map<String, String> NAMED = Map.ofEntries(
        Map.entry("nbsp",   "\u00a0"), Map.entry("amp",    "&"),
        Map.entry("lt",     "<"),      Map.entry("gt",     ">"),
        Map.entry("quot",   "\""),     Map.entry("apos",   "'"),
        Map.entry("eacute", "é"),      Map.entry("egrave", "è"),
        Map.entry("agrave", "à"),      Map.entry("ccedil", "ç"),
        Map.entry("ocirc",  "ô"),      Map.entry("ecirc",  "ê"),
        Map.entry("icirc",  "î"),      Map.entry("ugrave", "ù"),
        Map.entry("aacute", "á")
    );

    public static String stripScripts(String html) {
        return PAT_SCRIPT.matcher(html).replaceAll("");
    }

    public static String getText(String html) {
        return PAT_TAGS.matcher(html).replaceAll("");
    }

    public static List<String> extractElements(String html, String selector) {
        Pattern p = Pattern.compile(
            selectorToPattern(selector), Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        List<String> results = new ArrayList<>();
        while (m.find()) results.add(m.group(1));
        return results;
    }

    public static String extractAttribute(String element, String attr) {
        Matcher m = Pattern.compile(
            Pattern.quote(attr) + "=['\"]([^'\"]*)['\"]",
            Pattern.CASE_INSENSITIVE).matcher(element);
        return m.find() ? m.group(1) : null;
    }

    public static String decodeHtmlEntities(String text) {
        // Numeric decimal &#N;
        Matcher dm = PAT_DEC.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (dm.find()) {
            dm.appendReplacement(sb, Matcher.quoteReplacement(
                String.valueOf((char) Integer.parseInt(dm.group(1)))));
        }
        dm.appendTail(sb);
        text = sb.toString();

        // Numeric hex &#xHH;
        Matcher hm = PAT_HEX.matcher(text);
        sb = new StringBuffer();
        while (hm.find()) {
            hm.appendReplacement(sb, Matcher.quoteReplacement(
                String.valueOf((char) Integer.parseInt(hm.group(1), 16))));
        }
        hm.appendTail(sb);
        text = sb.toString();

        // Named &name;
        Matcher nm = PAT_NAM.matcher(text);
        sb = new StringBuffer();
        while (nm.find()) {
            String repl = NAMED.getOrDefault(nm.group(1), nm.group(0));
            nm.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        nm.appendTail(sb);
        return sb.toString();
    }

    public static boolean shouldSkipLink(String url, List<String> excluded) {
        if (!url.startsWith("http")) return true;
        return excluded.stream().anyMatch(url::contains);
    }

    private static String selectorToPattern(String sel) {
        if ("li.b_algo".equals(sel))
            return "<li[^>]*class=['\"]b_algo['\"][^>]*>(.*?)</li>";
        if ("div a".equals(sel))  return "<a[^>]*>(.*?)</a>";
        if ("div p".equals(sel))  return "<p[^>]*>(.*?)</p>";
        if (sel.startsWith(".")) {
            String cls = Pattern.quote(sel.substring(1));
            return "<[^>]*class=['\"][^'\"]*" + cls + "[^'\"]*['\"][^>]*>(.*?)</[^>]+>";
        }
        if (sel.startsWith("#")) {
            String id = Pattern.quote(sel.substring(1));
            return "<[^>]*id=['\"]" + id + "['\"][^>]*>(.*?)</[^>]+>";
        }
        if (sel.contains(".")) {
            int dot = sel.indexOf('.');
            String tag = Pattern.quote(sel.substring(0, dot));
            String cls = Pattern.quote(sel.substring(dot + 1));
            return "<" + tag + "[^>]*class=['\"][^'\"]*" + cls +
                   "[^'\"]*['\"][^>]*>(.*?)</" + tag + ">";
        }
        String tag = Pattern.quote(sel);
        return "<" + tag + "[^>]*>(.*?)</" + tag + ">";
    }
}

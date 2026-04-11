package org.emergence.emfilter.internal;

import org.emergence.emfilter.AgentConfig;
import org.emergence.emfilter.DiscoNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Resolves disco nodes and JWT token from config, env vars, and emergence.conf. */
public final class ConfigResolver {

    private ConfigResolver() {}

    public static List<DiscoNode> resolveNodes(AgentConfig config) {
        if (config.discoNodes != null && !config.discoNodes.isEmpty()) {
            return config.discoNodes;
        }

        String hostEnv = System.getenv("EM_DISCO_HOST");
        String portEnv = System.getenv("EM_DISCO_PORT");

        if (hostEnv != null && portEnv != null) {
            int port = parsePort(portEnv, 8080);
            return List.of(new DiscoNode(hostEnv, port, inferTls(hostEnv, port)));
        }
        if (hostEnv != null) {
            int[] pt = defaultPortTls(hostEnv);
            return List.of(new DiscoNode(hostEnv, pt[0], pt[1] == 1));
        }
        if (portEnv != null) {
            int port = parsePort(portEnv, 8080);
            return List.of(new DiscoNode("localhost", port, false));
        }

        List<DiscoNode> conf = readConfNodes();
        if (!conf.isEmpty()) return conf;

        return List.of(new DiscoNode("localhost", 8080, false));
    }

    public static String resolveJwt(AgentConfig config) {
        if (config.jwtToken != null && !config.jwtToken.isBlank()) {
            return config.jwtToken;
        }
        return System.getenv("EM_FILTER_JWT_TOKEN");
    }

    /** Visible for testing. */
    public static boolean inferTls(String host, int port) {
        if ("localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host)) {
            return false;
        }
        return port == 443;
    }

    private static int[] defaultPortTls(String host) {
        if ("localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host)) {
            return new int[]{8080, 0};
        }
        return new int[]{443, 1};
    }

    private static int parsePort(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static Path confPath() {
        String appdata = System.getenv("APPDATA");
        if (appdata != null) return Paths.get(appdata, "emergence", "emergence.conf");
        String home = System.getProperty("user.home");
        if (home == null) return null;
        String xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg != null) return Paths.get(xdg, "emergence", "emergence.conf");
        return Paths.get(home, ".config", "emergence", "emergence.conf");
    }

    private static List<DiscoNode> readConfNodes() {
        Path path = confPath();
        if (path == null || !Files.exists(path)) return List.of();
        try {
            return parseConf(Files.readString(path));
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Visible for testing. */
    public static List<DiscoNode> parseConf(String content) {
        String section = "";
        String lastNodes = null;
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
                continue;
            }
            if ("em_disco".equals(section) && line.contains("=")) {
                int eq = line.indexOf('=');
                if ("nodes".equals(line.substring(0, eq).trim())) {
                    lastNodes = line.substring(eq + 1).trim();
                }
            }
        }
        return lastNodes != null ? parseNodes(lastNodes) : List.of();
    }

    private static List<DiscoNode> parseNodes(String s) {
        List<DiscoNode> nodes = new ArrayList<>();
        for (String entry : s.split(",")) {
            entry = entry.trim();
            if (entry.isEmpty()) continue;
            // Strip IPv6 brackets: [::1]:9000 → host=::1, port=9000
            String host;
            int port;
            if (entry.startsWith("[")) {
                int closeBracket = entry.indexOf(']');
                if (closeBracket < 0) continue;
                host = entry.substring(1, closeBracket);
                String rest = entry.substring(closeBracket + 1);
                if (rest.startsWith(":")) {
                    port = parsePort(rest.substring(1), 8080);
                } else {
                    int[] pt = defaultPortTls(host);
                    port = pt[0];
                }
            } else if (entry.contains(":")) {
                int lastColon = entry.lastIndexOf(':');
                host = entry.substring(0, lastColon);
                port = parsePort(entry.substring(lastColon + 1), 8080);
            } else {
                host = entry;
                int[] pt = defaultPortTls(host);
                port = pt[0];
            }
            nodes.add(new DiscoNode(host, port, inferTls(host, port)));
        }
        return nodes;
    }
}

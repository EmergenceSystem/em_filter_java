package org.emergence.emfilter;

import java.util.List;
import java.util.Map;

/**
 * Handler contract for an Emergence filter agent.
 *
 * <p>Mirrors the Erlang em_filter handler contract:
 * {@code handle(Body, Memory) -> {Result, NewMemory}}
 *
 * <p>Example:
 * <pre>{@code
 * public class MyFilter implements Filter {
 *     public FilterResult handle(String body, Map<String, Object> memory) {
 *         List<Map<String, Object>> result = List.of(Map.of(
 *             "type", "url",
 *             "properties", Map.of("url", "https://example.com", "title", "Echo: " + body)
 *         ));
 *         return new FilterResult(result, memory);
 *     }
 * }
 * }</pre>
 */
public interface Filter {

    /**
     * Handle an incoming query from em_disco.
     *
     * @param body   raw query string (e.g. {@code "erlang otp"})
     * @param memory current memory state (empty map on first call; persisted across queries)
     * @return {@link FilterResult} containing the result data and the new memory state
     */
    FilterResult handle(String body, Map<String, Object> memory);

    /**
     * Capabilities announced in the {@code agent_hello} handshake frame.
     * em_disco uses these to route queries.
     *
     * @return list of capability strings; defaults to {@code ["search", "query"]}
     */
    default List<String> capabilities() {
        return List.of("search", "query");
    }
}

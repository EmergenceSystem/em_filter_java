package org.emergence.emfilter;

import java.util.Map;

/**
 * Result returned by {@link Filter#handle}.
 *
 * <p>Mirrors the Erlang {@code {Result, NewMemory}} tuple.
 */
public final class FilterResult {

    /** JSON-serialisable result data (typically a List of embryo Maps). */
    public final Object data;

    /** Updated memory state to persist for the next query. */
    public final Map<String, Object> memory;

    public FilterResult(Object data, Map<String, Object> memory) {
        this.data = data;
        this.memory = memory;
    }
}

package org.emergence.emfilter;

/** A single em_disco broker node. */
public final class DiscoNode {

    public final String host;
    public final int    port;
    public final boolean tls;

    public DiscoNode(String host, int port, boolean tls) {
        this.host = host;
        this.port = port;
        this.tls  = tls;
    }
}

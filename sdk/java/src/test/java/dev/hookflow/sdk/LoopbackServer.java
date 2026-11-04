package dev.hookflow.sdk;

import okhttp3.mockwebserver.MockWebServer;

import java.io.IOException;
import java.net.InetAddress;

/**
 * Starts MockWebServer on IPv4 loopback and hands out a URL built from the same
 * literal address.
 *
 * <p>MockWebServer's no-arg {@code start()} binds {@code localhost} and derives
 * its URL from the bound address's canonical hostname. On hosts where
 * {@code localhost} resolves to {@code ::1} first, the socket policy
 * {@code DISCONNECT_AT_START} makes OkHttp reopen the connection over IPv6 while
 * the listener is on IPv4, so the retry dies with "connection refused" before
 * the client's retry logic is exercised. Pinning both the listener and the base
 * URL to 127.0.0.1 removes that ambiguity.
 */
final class LoopbackServer {

    private static final String HOST = "127.0.0.1";
    private static final byte[] ADDRESS = {127, 0, 0, 1};

    private LoopbackServer() {
    }

    /** Starts a server bound to 127.0.0.1 on an ephemeral port. */
    static MockWebServer start() throws IOException {
        MockWebServer server = new MockWebServer();
        server.start(InetAddress.getByAddress(ADDRESS), 0);
        return server;
    }

    /** The base URL for {@code server}, using 127.0.0.1 rather than localhost. */
    static String baseUrl(MockWebServer server) {
        return "http://" + HOST + ":" + server.getPort() + "/";
    }
}

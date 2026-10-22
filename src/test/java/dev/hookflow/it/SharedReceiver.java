package dev.hookflow.it;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * One HTTP receiver shared by every IT class in this JVM.
 *
 * <p>Starting a fresh server per class would make the port a moving target and add
 * seconds to the suite for no benefit; these tests only ever assert that a call
 * arrived, not that it arrived at a particular server instance.
 */
final class SharedReceiver {

    private static final HttpServer SERVER;

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.setExecutor(Executors.newFixedThreadPool(8));
            SERVER.createContext("/hook", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            SERVER.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> SERVER.stop(0)));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start the shared test receiver", e);
        }
    }

    private SharedReceiver() {
    }

    static int port() {
        return SERVER.getAddress().getPort();
    }
}

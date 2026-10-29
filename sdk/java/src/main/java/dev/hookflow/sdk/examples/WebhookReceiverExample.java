package dev.hookflow.sdk.examples;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.hookflow.sdk.HookFlowWebhook;
import dev.hookflow.sdk.Verification;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A runnable receiver built on the JDK's HTTP server, so it needs no framework.
 *
 * <p>It demonstrates the two things that make verification work: reading the raw
 * body bytes before any parsing, and passing the headers through a case-insensitive
 * map.
 *
 * <p>Run with {@code HOOKFLOW_WEBHOOK_SECRET} set, then point an endpoint at
 * {@code http://localhost:9000/webhooks/hookflow}.
 */
public final class WebhookReceiverExample {

    private static final int PORT = 9000;

    private WebhookReceiverExample() {
    }

    /**
     * Starts the receiver and blocks until the process is killed.
     *
     * @param args ignored
     * @throws IOException if the port cannot be bound
     */
    public static void main(String[] args) throws IOException {
        String secret = System.getenv().getOrDefault("HOOKFLOW_WEBHOOK_SECRET", "s3cr3t");
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/webhooks/hookflow", exchange -> handle(exchange, secret));
        server.start();
        System.out.println("listening on http://localhost:" + PORT + "/webhooks/hookflow");
    }

    private static void handle(HttpExchange exchange, String secret) throws IOException {
        // Raw bytes, not a parsed object: re-serialising would break the signature.
        byte[] rawBody = exchange.getRequestBody().readAllBytes();

        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });

        Verification verification = HookFlowWebhook.verify(secret, headers, rawBody);
        if (!verification.valid()) {
            System.out.println("rejected webhook: " + verification.reason());
            respond(exchange, 401, "invalid signature: " + verification.reason());
            return;
        }

        System.out.println("accepted webhook, " + rawBody.length + " bytes: "
                + new String(rawBody, StandardCharsets.UTF_8));
        // Acknowledge quickly. HookFlow retries non-2xx responses, so a slow handler
        // here looks like a failing endpoint.
        respond(exchange, 200, "ok");
    }

    private static void respond(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().put("Content-Type", List.of("text/plain; charset=utf-8"));
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}

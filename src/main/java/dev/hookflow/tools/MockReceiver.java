package dev.hookflow.tools;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.hookflow.crypto.HmacSigner;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A deliberately dumb customer endpoint, used by the Quick Start and the fault
 * drills.
 *
 * <pre>
 *   java -cp target/classes dev.hookflow.tools.MockReceiver 9099 [base64url-secret]
 * </pre>
 *
 * <p>Behaviour is driven by query parameters, so a failure scenario needs no
 * code change and no restart:
 * <ul>
 *   <li>{@code ?status=500} — always answer with that status.</li>
 *   <li>{@code ?delay=3000} — sleep before answering (drives read timeouts).</li>
 *   <li>{@code /received} — the last 50 requests, with signature verdicts.</li>
 * </ul>
 *
 * <p>When a secret is supplied the receiver verifies the HMAC itself: the point
 * of the demo is that a receiver can validate the signature without trusting
 * this service.
 */
public final class MockReceiver {

    private static final int HISTORY = 50;
    private static final Deque<String> RECEIVED = new ArrayDeque<>();

    /**
     * Benchmark mode: no per-request console output.
     *
     * <p>Printing one line per webhook is fine for a demo and catastrophic for a
     * load test — the receiver becomes the bottleneck and the measured throughput
     * describes System.out, not HookFlow.
     */
    private static volatile boolean quiet;

    private MockReceiver() {
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9099;
        byte[] secret = args.length > 1 && !args[1].isBlank()
                ? Base64.getUrlDecoder().decode(args[1])
                : null;
        quiet = args.length > 2 && "quiet".equalsIgnoreCase(args[2]);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        // The receiver must be able to take more parallel calls than the worker
        // pool it is meant to observe, otherwise IT becomes the measured system.
        server.setExecutor(Executors.newFixedThreadPool(
                Integer.getInteger("hookflow.mock.threads", 32)));

        server.createContext("/hook", exchange -> {
            long start = System.nanoTime();
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQuery(query);
            int status = Integer.parseInt(params.getOrDefault("status", "200"));
            long delayMs = Long.parseLong(params.getOrDefault("delay", "0"));
            byte[] body = exchange.getRequestBody().readAllBytes();

            boolean signatureValid = true;
            if (secret != null) {
                String header = exchange.getRequestHeaders().getFirst("X-HookFlow-Signature");
                String timestamp = exchange.getRequestHeaders().getFirst("X-HookFlow-Timestamp");
                String eventId = exchange.getRequestHeaders().getFirst("X-HookFlow-Event-Id");
                String deliveryId = exchange.getRequestHeaders().getFirst("X-HookFlow-Delivery-Id");
                signatureValid = header != null && timestamp != null && eventId != null && deliveryId != null
                        && HmacSigner.verify(secret, Long.parseLong(eventId), Long.parseLong(deliveryId),
                        Long.parseLong(timestamp), body, header);
            }

            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            String record = "status=" + status
                    + " attempt=" + exchange.getRequestHeaders().getFirst("X-HookFlow-Attempt")
                    + " signature_valid=" + signatureValid
                    + " bytes=" + body.length
                    + " ms=" + ((System.nanoTime() - start) / 1_000_000L)
                    + " body=" + abbreviate(new String(body, StandardCharsets.UTF_8));
            remember(record);
            if (!quiet) {
                System.out.println("[MockReceiver] " + record);
            }

            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });

        server.createContext("/received", exchange -> {
            StringBuilder sb = new StringBuilder("{\"count\":").append(RECEIVED.size()).append(",\"items\":[");
            boolean first = true;
            synchronized (RECEIVED) {
                for (String item : RECEIVED) {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append('"').append(item.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
                    first = false;
                }
            }
            sb.append("]}");
            respond(exchange, 200, sb.toString());
        });

        server.createContext("/reset", exchange -> {
            synchronized (RECEIVED) {
                RECEIVED.clear();
            }
            respond(exchange, 200, "{\"ok\":true}");
        });

        server.start();
        System.out.println("[MockReceiver] listening on http://localhost:" + port + "/hook"
                + (secret == null ? " (signature verification disabled)" : " (signature verification ON)"));
        System.out.println("[MockReceiver] try /hook?status=500 or /hook?delay=3000; history at /received");
    }

    private static void remember(String record) {
        synchronized (RECEIVED) {
            RECEIVED.addFirst(record);
            while (RECEIVED.size() > HISTORY) {
                RECEIVED.removeLast();
            }
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(String query) {
        if (query == null || query.isBlank()) {
            return Map.of();
        }
        java.util.HashMap<String, String> params = new java.util.HashMap<>();
        for (String pair : query.split("&")) {
            int split = pair.indexOf('=');
            if (split > 0) {
                params.put(pair.substring(0, split), pair.substring(split + 1));
            }
        }
        return params;
    }

    private static String abbreviate(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }

    /** Kept to make the unused-import checker happy in IDEs without warnings. */
    static List<String> emptyHistory() {
        return List.copyOf(RECEIVED);
    }

    static final AtomicInteger UNUSED = new AtomicInteger();
}

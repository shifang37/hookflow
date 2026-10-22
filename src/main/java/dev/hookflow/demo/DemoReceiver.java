package dev.hookflow.demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.hookflow.crypto.HmacSigner;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The customer's server in the demo: a real HTTP endpoint that receives real
 * webhooks, verifies the HMAC signature itself, and can be told to misbehave.
 *
 * <p>It lives in the same process as the platform for one reason only — so the
 * browser can show what a receiver actually got. Everything it does is what a
 * customer's own code would do: read the raw body, recompute the signature from the
 * secret, compare.
 *
 * <p>Modes exist because "it works" is the least interesting thing a delivery
 * pipeline can do:
 *
 * <ul>
 *   <li>{@code OK} — 200, the happy path.</li>
 *   <li>{@code ERROR} — 500, a customer bug or outage: retried with backoff.</li>
 *   <li>{@code SLOW} — sleeps past the read timeout, which is how a hung receiver
 *       looks from the outside: {@code TIMEOUT}, retried.</li>
 *   <li>{@code REJECT} — drops the connection instead of answering: looks like a
 *       crashed or unreachable host, {@code CONNECT_ERROR}, retried. Recovering from
 *       it needs no operator action at all, which is the point.</li>
 * </ul>
 *
 * <p>Secrets are keyed by the tenant slug embedded in the URL
 * ({@code /hook/acme}); the endpoint is created with that URL, so no lookup table
 * between a delivery and a secret is needed here.
 */
@Component
@Profile("all")
@ConditionalOnProperty(prefix = "hookflow.demo", name = "enabled", havingValue = "true")
public class DemoReceiver {

    private static final Logger log = LoggerFactory.getLogger(DemoReceiver.class);

    /** How the fake customer behaves on the next webhook. */
    public enum Mode {
        OK(200), ERROR(500), SLOW(200), REJECT(200);

        private final int status;

        Mode(int status) {
            this.status = status;
        }
    }

    /** One webhook as the customer saw it. */
    public record Received(String at,
                           String slug,
                           long eventId,
                           long deliveryId,
                           int attempt,
                           String eventType,
                           boolean signatureValid,
                           int bytes,
                           long handlerMs,
                           String traceId) {
    }

    private static final int HISTORY = 40;

    private final int port;
    private final Deque<Received> recent = new ArrayDeque<>();
    private final Map<String, byte[]> secrets = new ConcurrentHashMap<>();
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong signatureOk = new AtomicLong();
    private final AtomicLong signatureBad = new AtomicLong();
    private final AtomicLong rejects = new AtomicLong();

    private volatile Mode mode = Mode.OK;
    /** Answer delay; {@link Mode#SLOW} ignores it and uses {@link #slowMillis} instead. */
    private volatile long delayMillis = 0;
    private volatile long slowMillis = 5_000;
    private HttpServer server;

    public DemoReceiver(dev.hookflow.config.HookFlowProperties properties) {
        this.port = properties.demo().receiverPort();
    }

    @PostConstruct
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/hook", this::handle);
        server.start();
        log.info("Demo customer endpoint listening on http://127.0.0.1:{}/hook/<tenant>", port);
    }

    @PreDestroy
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int port() {
        return port;
    }

    public void registerSecret(String slug, byte[] secret) {
        secrets.put(slug, secret);
    }

    public Mode mode() {
        return mode;
    }

    public void mode(Mode mode) {
        this.mode = mode;
    }

    public long slowMillis() {
        return slowMillis;
    }

    public long delayMillis() {
        return delayMillis;
    }

    /** Manual delay in ms; capped so a click cannot pin a worker thread for minutes. */
    public void delayMillis(long millis) {
        this.delayMillis = Math.max(0, Math.min(2_000, millis));
    }

    public java.util.List<Received> recent() {
        synchronized (recent) {
            return java.util.List.copyOf(recent);
        }
    }

    public long receivedCount() {
        return received.get();
    }

    public long signatureOkCount() {
        return signatureOk.get();
    }

    public long signatureBadCount() {
        return signatureBad.get();
    }

    public long rejectCount() {
        return rejects.get();
    }

    public void resetCounters() {
        received.set(0);
        signatureOk.set(0);
        signatureBad.set(0);
        rejects.set(0);
        synchronized (recent) {
            recent.clear();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        long started = System.nanoTime();
        String path = exchange.getRequestURI().getPath();
        String slug = path.substring(path.lastIndexOf('/') + 1);
        byte[] body = exchange.getRequestBody().readAllBytes();

        if (mode == Mode.REJECT) {
            // No status line, no body: the client sees the connection die, which is
            // exactly what a crashed receiver looks like.
            rejects.incrementAndGet();
            exchange.close();
            return;
        }

        boolean signatureValid = verify(slug, exchange, body);
        received.incrementAndGet();
        if (signatureValid) {
            signatureOk.incrementAndGet();
        } else {
            signatureBad.incrementAndGet();
        }

        remember(new Received(
                java.time.Instant.now().toString(),
                slug,
                longHeader(exchange, "X-HookFlow-Event-Id"),
                longHeader(exchange, "X-HookFlow-Delivery-Id"),
                (int) longHeader(exchange, "X-HookFlow-Attempt"),
                exchange.getRequestHeaders().getFirst("X-HookFlow-Event-Type"),
                signatureValid,
                body.length,
                (System.nanoTime() - started) / 1_000_000L,
                traceId(exchange)));

        if (mode == Mode.SLOW) {
            sleep(slowMillis);
        } else if (delayMillis > 0) {
            sleep(delayMillis);
        }

        int status = mode == Mode.ERROR ? 500 : 200;
        byte[] response = (status == 200 ? "{\"ok\":true}" : "{\"error\":\"customer says no\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }

    private boolean verify(String slug, HttpExchange exchange, byte[] body) {
        byte[] secret = secrets.get(slug);
        if (secret == null) {
            return false;
        }
        String signature = exchange.getRequestHeaders().getFirst("X-HookFlow-Signature");
        String timestamp = exchange.getRequestHeaders().getFirst("X-HookFlow-Timestamp");
        if (signature == null || timestamp == null) {
            return false;
        }
        try {
            return HmacSigner.verify(secret,
                    longHeader(exchange, "X-HookFlow-Event-Id"),
                    longHeader(exchange, "X-HookFlow-Delivery-Id"),
                    Long.parseLong(timestamp), body, signature);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** W3C trace id (second field of traceparent), empty when tracing is off. */
    private static String traceId(HttpExchange exchange) {
        String traceparent = exchange.getRequestHeaders().getFirst("traceparent");
        if (traceparent == null) {
            return "";
        }
        String[] parts = traceparent.split("-");
        return parts.length >= 2 ? parts[1] : "";
    }

    private static long longHeader(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void remember(Received item) {
        synchronized (recent) {
            recent.addFirst(item);
            while (recent.size() > HISTORY) {
                recent.removeLast();
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

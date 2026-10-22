package dev.hookflow.demo;

import dev.hookflow.config.HookFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A simulated business system: an order service that emits its own lifecycle events
 * to hookflow over the <b>public HTTP API</b>, with a real API key.
 *
 * <p>That constraint is deliberate. It would have been five lines shorter to call
 * {@code EventIngestService} directly, and the demo would then be lying: it would
 * skip authentication, the rate limiter, the backlog gate, HTTP serialisation and
 * the 429 that a real customer experiences when the platform sheds load. Those are
 * exactly the behaviours the console exists to show.
 *
 * <p>Each tenant gets its own order stream. An order moves
 * {@code order.created -> order.paid -> order.shipped} over a few ticks, so the
 * traffic has a shape (bursts, fan-out per event type, retries with the same
 * idempotency key) instead of being a random drip of unrelated payloads.
 */
@Component
@Profile("all")
@ConditionalOnProperty(prefix = "hookflow.demo", name = "enabled", havingValue = "true")
public class BusinessSimulator implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(BusinessSimulator.class);

    private static final long TICK_MILLIS = 200;
    /** Hard cap per tick so a rate slider cannot turn into a thundering herd. */
    private static final int MAX_EVENTS_PER_TICK = 40;
    private static final int MAX_OPEN_ORDERS = 12;

    private static final String[] CUSTOMERS = {"northwind", "contoso", "fabrikam", "adventure"};
    private static final String[] CHANNELS = {"web", "ios", "android", "pos"};

    private enum Stage {
        CREATED("order.created"), PAID("order.paid"), SHIPPED("order.shipped");

        private final String eventType;

        Stage(String eventType) {
            this.eventType = eventType;
        }

        String eventType() {
            return eventType;
        }
    }

    /** Counters the console shows next to each tenant. */
    public record StreamStats(String slug,
                              String tenantName,
                              int ratePerSecond,
                              long sent,
                              long accepted,
                              long throttled,
                              long failed,
                              int openOrders,
                              String lastError) {
    }

    private static final class Stream {
        private final DemoTenant tenant;
        private final Deque<String[]> openOrders = new ArrayDeque<>();
        private final AtomicLong sent = new AtomicLong();
        private final AtomicLong accepted = new AtomicLong();
        private final AtomicLong throttled = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private volatile String lastError;
        private volatile int ratePerSecond;
        private double credit;
        private final Random random = new Random();

        Stream(DemoTenant tenant, int ratePerSecond) {
            this.tenant = tenant;
            this.ratePerSecond = ratePerSecond;
        }
    }

    private final Map<Long, Stream> streams = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong burstRemaining = new AtomicLong();
    private final Random random = new Random();
    private final ExecutorService senders = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "demo-sender");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "demo-ticker");
        thread.setDaemon(true);
        return thread;
    });
    /** Separate from the ticker so a burst cannot stall the steady stream. */
    private final ScheduledExecutorService burstScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "demo-burst");
                thread.setDaemon(true);
                return thread;
            });
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final DemoRegistry registry;
    private final Environment environment;
    private final int defaultRate;

    /** Resolved on ApplicationReadyEvent: the port exists only once the server runs. */
    private volatile String baseUrl = "http://127.0.0.1:8080";

    public BusinessSimulator(DemoRegistry registry, HookFlowProperties properties,
                             Environment environment) {
        this.registry = registry;
        this.environment = environment;
        this.defaultRate = properties.demo().simulatorRatePerSecond();
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // local.server.port is what the test framework sets for a random port;
        // server.port is what a real run uses. Reading either avoids depending on
        // Boot-internal context types, whose packages move between major versions.
        Integer port = environment.getProperty("local.server.port", Integer.class);
        if (port == null) {
            port = environment.getProperty("server.port", Integer.class, 8080);
        }
        baseUrl = "http://127.0.0.1:" + port;
        for (DemoTenant tenant : registry.tenants()) {
            streams.put(tenant.tenantId(), new Stream(tenant, defaultRate));
        }
        ticker.scheduleWithFixedDelay(this::tick, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
        log.info("Simulated business systems running against {} at {} events/s per tenant",
                baseUrl, defaultRate);
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        try {
            if (!running.get()) {
                return;
            }
            for (Stream stream : streams.values()) {
                stream.credit += stream.ratePerSecond * (TICK_MILLIS / 1000.0);
                int emitted = 0;
                while (stream.credit >= 1 && emitted < MAX_EVENTS_PER_TICK) {
                    stream.credit -= 1;
                    emitted++;
                    emit(stream);
                }
            }
        } catch (RuntimeException e) {
            // A broken demo tick must never take the app down with it.
            log.debug("simulator tick failed", e);
        }
    }

    private void emit(Stream stream) {
        String[] order = nextOrder(stream);
        if (order == null) {
            return;
        }
        String orderId = order[0];
        Stage stage = Stage.valueOf(order[1]);
        send(stream, orderId, stage.eventType(), payload(stream, orderId, stage));
    }

    /**
     * Advances the tenant's order book by one event: mostly the next stage of an
     * existing order, sometimes a brand new one. That mix is what makes the console's
     * event feed look like a business, and it is also why an event fans out to
     * different endpoints (the {@code quick} tenant only subscribes to
     * {@code order.paid}).
     *
     * @return {@code [orderId, stage]} or null when nothing can be advanced
     */
    private String[] nextOrder(Stream stream) {
        // Synchronise on the stream, not on the simulator: two tenants must not
        // serialise each other's order book.
        synchronized (stream) {
            String[] existing = stream.openOrders.peekFirst();
            if (existing == null || stream.random.nextInt(100) < 30
                    || Stage.valueOf(existing[1]) == Stage.SHIPPED) {
                String orderId = "ORD-" + (1000 + stream.random.nextInt(9000));
                String[] order = {orderId, Stage.CREATED.name()};
                stream.openOrders.addLast(order);
                trim(stream);
                return order;
            }
            stream.openOrders.removeFirst();
            Stage next = switch (Stage.valueOf(existing[1])) {
                case CREATED -> Stage.PAID;
                case PAID -> Stage.SHIPPED;
                case SHIPPED -> Stage.SHIPPED;
            };
            existing[1] = next.name();
            stream.openOrders.addLast(existing);
            return existing;
        }
    }

    private void trim(Stream stream) {
        while (stream.openOrders.size() > MAX_OPEN_ORDERS) {
            stream.openOrders.removeFirst();
        }
    }

    private String payload(Stream stream, String orderId, Stage stage) {
        String customer = CUSTOMERS[Math.abs(orderId.hashCode()) % CUSTOMERS.length];
        String channel = CHANNELS[stream.random.nextInt(CHANNELS.length)];
        int amount = 1000 + stream.random.nextInt(90_000);
        return "{"
                + "\"order_id\":\"" + orderId + "\","
                + "\"stage\":\"" + stage.name().toLowerCase(java.util.Locale.ROOT) + "\","
                + "\"customer\":{\"id\":\"C-" + (100 + stream.random.nextInt(900)) + "\","
                + "\"name\":\"" + customer + "\"},"
                + "\"channel\":\"" + channel + "\","
                + "\"amount_cents\":" + amount + ","
                + "\"currency\":\"CNY\","
                + "\"items\":[{\"sku\":\"SKU-" + (10 + stream.random.nextInt(90)) + "\","
                + "\"qty\":" + (1 + stream.random.nextInt(3)) + "}],"
                + "\"occurred_at\":\"" + java.time.Instant.now() + "\""
                + "}";
    }

    private void send(Stream stream, String orderId, String eventType, String payload) {
        senders.execute(() -> post(stream, orderId + "-" + eventType, eventType, payload));
    }

    private void post(Stream stream, String idempotencyKey, String eventType, String payload) {
        stream.sent.incrementAndGet();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/events"))
                .header("Authorization", "Bearer " + stream.tenant.apiKey())
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "demo-" + stream.tenant.slug() + "-" + idempotencyKey)
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"eventType\":\"" + eventType + "\",\"payload\":" + payload + "}",
                        StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                stream.accepted.incrementAndGet();
            } else if (response.statusCode() == 429) {
                stream.throttled.incrementAndGet();
                stream.lastError = "429 平台降载";
            } else if (response.statusCode() == 503) {
                stream.throttled.incrementAndGet();
                stream.lastError = "503 平台过载";
            } else {
                stream.failed.incrementAndGet();
                stream.lastError = response.statusCode() + " 业务系统收到错误";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            stream.failed.incrementAndGet();
            stream.lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    // --------------------------------------------------------------- controls

    public boolean running() {
        return running.get();
    }

    public void running(boolean running) {
        this.running.set(running);
    }

    /** @return the rate actually applied (per tenant) */
    public int rate(int requested) {
        int rate = Math.max(0, Math.min(200, requested));
        for (Stream stream : streams.values()) {
            stream.ratePerSecond = rate;
        }
        return rate;
    }

    public int rate() {
        return streams.values().stream().mapToInt(s -> s.ratePerSecond).findFirst().orElse(0);
    }

    /**
     * Fires {@code count} events as fast as {@code perSecond} allows, through the same
     * HTTP path as the steady stream. This is the console's "flood" button: it is how
     * you make one tenant own the queue.
     *
     * @return how many were accepted for sending (may be capped by the queue)
     */
    public long burst(long tenantId, int count, int perSecond) {
        Stream stream = streams.get(tenantId);
        if (stream == null) {
            return 0;
        }
        int events = Math.max(0, Math.min(5_000, count));
        burstRemaining.addAndGet(events);
        long intervalNanos = perSecond <= 0 ? 0 : 1_000_000_000L / perSecond;
        long start = System.nanoTime();
        for (int i = 0; i < events; i++) {
            final int index = i;
            burstScheduler.execute(() -> {
                if (intervalNanos > 0) {
                    parkUntil(start + index * intervalNanos);
                }
                burstRemaining.decrementAndGet();
                emit(stream);
            });
        }
        return events;
    }

    public long burstRemaining() {
        return Math.max(0, burstRemaining.get());
    }

    /**
     * One event, in the exact shape a customer would send it. Used by the console's
     * "submit one event" button, and by tests that need a deterministic delivery.
     */
    public String submitOne(long tenantId) {
        Stream stream = streams.get(tenantId);
        if (stream == null) {
            return null;
        }
        String[] order = nextOrder(stream);
        if (order == null) {
            return null;
        }
        String orderId = order[0];
        Stage stage = Stage.valueOf(order[1]);
        send(stream, orderId, stage.eventType(), payload(stream, orderId, stage));
        return orderId;
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        ticker.shutdownNow();
        burstScheduler.shutdownNow();
        senders.shutdownNow();
    }

    public List<StreamStats> stats() {
        return streams.values().stream()
                .map(stream -> new StreamStats(
                        stream.tenant.slug(),
                        stream.tenant.name(),
                        stream.ratePerSecond,
                        stream.sent.get(),
                        stream.accepted.get(),
                        stream.throttled.get(),
                        stream.failed.get(),
                        stream.openOrders.size(),
                        stream.lastError))
                .toList();
    }

    public void resetCounters() {
        for (Stream stream : streams.values()) {
            stream.sent.set(0);
            stream.accepted.set(0);
            stream.throttled.set(0);
            stream.failed.set(0);
            stream.lastError = null;
        }
    }

    private static void parkUntil(long dueNanos) {
        while (true) {
            long remaining = dueNanos - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(remaining);
        }
    }
}

package dev.hookflow.it;

import com.sun.net.httpserver.HttpServer;
import dev.hookflow.domain.delivery.Delivery;
import dev.hookflow.domain.delivery.DeliveryStatus;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.persistence.OutboxMapper;
import dev.hookflow.persistence.TenantMapper;
import dev.hookflow.service.ApiKeyService;
import dev.hookflow.service.EndpointService;
import dev.hookflow.service.EventIngestService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The measurement this release exists for.</b>
 *
 * <p>Two tenants share one platform. Tenant A submits a flood; tenant B submits
 * exactly one event. The question is not "is the platform fast" but "does B's
 * single event pay for A's flood" — and the honest answer is a ratio, not an
 * adjective.
 *
 * <p>The scenario runs twice with exactly one thing changed: A's concurrency
 * budget.
 *
 * <ul>
 *   <li><b>Unbounded</b> ({@code max_in_flight = 0}) reproduces v0.2: the relay
 *       publishes in outbox id order and the budget check is a no-op, so A's 1 500
 *       messages end up in one FIFO broker queue with B's single message behind
 *       all of them.</li>
 *   <li><b>Bounded</b> ({@code max_in_flight = 4}) is v0.3: at most four of A's
 *       messages can be in flight, so at most four can ever be in front of B. The
 *       rest of A's backlog waits in MySQL, where it is invisible to B.</li>
 * </ul>
 *
 * <p>Both runs use the same receiver latency, flood size and worker pool, so the
 * difference is attributable to the scheduler. The numbers are printed as
 * {@code FAIRNESS[...]} lines and the assertions are deliberately loose: this
 * demonstrates a mechanism, not a machine.
 */
class FairnessIT extends AbstractIntegrationTest {

    /**
     * Receiver latency. It only decides how fast the flood drains; the queue itself
     * is built by submitting the flood faster than it drains (see {@link #measure}).
     */
    private static final int RECEIVER_DELAY_MS = 50;

    /** Flood size; {@code -Dhookflow.it.flood=10000} for the full-scale run. */
    private static final int FLOOD_EVENTS = Integer.getInteger("hookflow.it.flood", 1_500);

    /** Events per batch when submitting the flood (max is 500 on the API). */
    private static final int FLOOD_BATCH = 100;

    private static HttpServer receiver;
    private static int receiverPort;

    @AfterAll
    static void stopReceiver() {
        if (receiver != null) {
            receiver.stop(0);
        }
    }

    static {
        try {
            receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            receiver.setExecutor(Executors.newFixedThreadPool(64));
            receiver.createContext("/slow", exchange -> {
                exchange.getRequestBody().readAllBytes();
                try {
                    Thread.sleep(RECEIVER_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            receiver.start();
            receiverPort = receiver.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start the slow receiver", e);
        }
    }

    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private EndpointService endpointService;
    @Autowired
    private EventIngestService ingestService;
    @Autowired
    private DeliveryMapper deliveryMapper;
    @Autowired
    private OutboxMapper outboxMapper;

    @Test
    @DisplayName("the only difference between 4 s and 0.1 s is one concurrency budget")
    void concurrencyBudgetRemovesStarvation() {
        Measurement bounded = measure(4);
        report("bounded", bounded);

        Measurement unbounded = measure(0);
        report("unbounded", unbounded);

        double ratio = unbounded.victimLatencyMs() / (double) Math.max(1, bounded.victimLatencyMs());
        System.out.println(String.format(Locale.ROOT,
                "FAIRNESS[summary] starvationRatio=%.1fx bounded=%dms unbounded=%dms floodEvents=%d",
                ratio, bounded.victimLatencyMs(), unbounded.victimLatencyMs(), FLOOD_EVENTS));

        assertThat(bounded.victimLatencyMs())
                .as("with a budget the victim waits behind at most max_in_flight messages")
                .isLessThan(2_000);
        assertThat(unbounded.victimLatencyMs())
                .as("without a budget the victim waits behind the whole flood")
                .isGreaterThan(2_000);
        assertThat(ratio)
                .as("the two runs differ only in the budget, so the ratio is the scheduler's doing")
                .isGreaterThan(3.0);
    }

    // ---------------------------------------------------------------- helpers

    private record Measurement(long victimLatencyMs, long floodEvents, long backlogAtSubmit) {
    }

    /**
     * Floods one tenant, then submits one event as the victim and times it end to
     * end (ingest accepted -> delivery SUCCEEDED).
     *
     * <p>The flood goes in through the <b>batch</b> endpoint. That is not a shortcut:
     * the queue a victim waits behind only exists while more work arrives than the
     * worker can drain, so the flood has to be submitted faster than the worker
     * consumes it. Ingesting one event at a time made that depend on how slow the
     * rest of the system happened to be, and v0.4 broke it twice — first by making
     * the relay fast (B4), then by doubling the worker's concurrency (B5). Batching
     * the flood removes the dependence: 1 500 events land in ~1 s while the worker
     * drains ~40–160/s, so the backlog is real regardless of how the platform is
     * tuned.
     */
    private Measurement measure(int flooderBudget) {
        long floodTenant = newTenant("flood", flooderBudget);
        long victimTenant = newTenant("victim", 32);
        createEndpoint(floodTenant);
        createEndpoint(victimTenant);

        for (int offset = 0; offset < FLOOD_EVENTS; offset += FLOOD_BATCH) {
            List<EventIngestService.BatchItem> items = new ArrayList<>(FLOOD_BATCH);
            int end = Math.min(offset + FLOOD_BATCH, FLOOD_EVENTS);
            for (int i = offset; i < end; i++) {
                items.add(new EventIngestService.BatchItem("order.paid", "{\"amount\":" + i + "}",
                        "flood-" + UUID.randomUUID()));
            }
            ingestService.ingestBatch(floodTenant, items);
        }
        long backlogAtSubmit = outboxMapper.countUnpublished();

        long start = System.nanoTime();
        long victimEventId = ingestService.ingest(victimTenant, "order.paid",
                "{\"amount\":1}", "victim-" + UUID.randomUUID()).eventId();
        awaitStatus(victimTenant, victimEventId, DeliveryStatus.SUCCEEDED, Duration.ofMinutes(3));
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;

        // Let the flood finish before the next measurement. Pausing the endpoint
        // instead would be faster but turns this test into a test of "cancel 1 500
        // rows while the workers claim them", which is a different experiment (and
        // the one that found docs/failures/0007).
        awaitFloodDrained(floodTenant, Duration.ofMinutes(5));

        return new Measurement(latencyMs, FLOOD_EVENTS, backlogAtSubmit);
    }

    private long newTenant(String label, int maxInFlight) {
        String name = label + "-" + UUID.randomUUID();
        tenantMapper.insert(name, 0);
        long tenantId = tenantMapper.findByName(name).orElseThrow().id();
        tenantMapper.updateBudget(tenantId, maxInFlight, 1);
        apiKeyService.issue(tenantId, "event:write,endpoint:write,delivery:read");
        return tenantId;
    }

    private long createEndpoint(long tenantId) {
        return endpointService.create(tenantId, "http://127.0.0.1:" + receiverPort + "/slow", "*", 3)
                .endpoint().id();
    }

    private void awaitFloodDrained(long tenantId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            boolean quiet = deliveryMapper.count(tenantId, null, DeliveryStatus.PENDING.name()) == 0
                    && deliveryMapper.count(tenantId, null, DeliveryStatus.DELIVERING.name()) == 0
                    && deliveryMapper.count(tenantId, null, DeliveryStatus.RETRY_WAIT.name()) == 0;
            if (quiet) {
                return;
            }
            sleep(200);
        }
        throw new AssertionError("Flood did not drain within " + timeout
                + "; pending=" + deliveryMapper.count(tenantId, null, DeliveryStatus.PENDING.name())
                + " delivering=" + deliveryMapper.count(tenantId, null, DeliveryStatus.DELIVERING.name()));
    }

    private Delivery awaitStatus(long tenantId, long eventId, DeliveryStatus expected, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        Delivery latest = null;
        while (Instant.now().isBefore(deadline)) {
            List<Delivery> deliveries = deliveryMapper.list(tenantId, eventId, null, 10, 0);
            if (!deliveries.isEmpty()) {
                latest = deliveryMapper.findById(deliveries.getFirst().id()).orElse(null);
                if (latest != null && latest.status() == expected) {
                    return latest;
                }
            }
            sleep(50);
        }
        throw new AssertionError("Victim delivery did not reach " + expected + " within " + timeout
                + "; last state=" + (latest == null ? "none" : latest.status())
                + "; unpublishedOutbox=" + outboxMapper.countUnpublished());
    }

    private static void report(String label, Measurement measurement) {
        System.out.println("FAIRNESS[" + label + "] floodEvents=" + measurement.floodEvents()
                + " backlogAtSubmit=" + measurement.backlogAtSubmit()
                + " victimLatencyMs=" + measurement.victimLatencyMs());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

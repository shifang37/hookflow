package dev.hookflow.demo;

import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.domain.delivery.Delivery;
import dev.hookflow.fairness.AdmissionControl;
import dev.hookflow.common.error.ApiException;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.persistence.OutboxMapper;
import dev.hookflow.service.DeliveryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The demo console's API: one {@code GET /state} for the page, plus the buttons.
 *
 * <h2>This endpoint is not authenticated, on purpose, and that is a demo decision</h2>
 *
 * <p>Every other {@code /v1/**} endpoint requires an API key. This one cannot: the
 * page lives in a browser with no credential, and putting a tenant key in
 * JavaScript would be worse than the problem it solves — it would look like a
 * security boundary while being readable in dev tools.
 *
 * <p>So the whole console is a <b>conditional bean</b>: it only exists when
 * {@code hookflow.demo.enabled=true}, which is set for the local {@code all} profile
 * and nowhere else. In a deployment these paths 404 because the controller was never
 * created. That is the honest version of "demo only": not a password in the UI, but
 * a bean that does not exist.
 *
 * <p>What it does is deliberately thin. Buttons start the simulated business systems
 * and change tenant budgets; the traffic those buttons cause travels the real path
 * (HTTP + API key + rate limiter + backlog gate + MySQL + broker + worker). The page
 * then reads the same tables an operator would query, so a number on the screen is
 * never a number the console invented.
 */
@RestController
@RequestMapping("/v1/demo")
@Profile("all")
@ConditionalOnProperty(prefix = "hookflow.demo", name = "enabled", havingValue = "true")
public class DemoController {

    /** Jaeger's default local UI; shown as a link only. */
    private static final String JAEGER_UI = "http://localhost:16686";

    private static final int DELIVERY_FEED_SIZE = 40;
    private static final int LATENCY_SAMPLE_SIZE = 200;

    private final DemoRegistry registry;
    private final BusinessSimulator simulator;
    private final DemoReceiver receiver;
    private final DemoMapper demoMapper;
    private final OutboxMapper outboxMapper;
    private final DeliveryMapper deliveryMapper;
    private final DeliveryService deliveryService;
    private final AdmissionControl admission;
    private final MeterRegistry meterRegistry;
    private final HookFlowProperties properties;

    public DemoController(DemoRegistry registry,
                          BusinessSimulator simulator,
                          DemoReceiver receiver,
                          DemoMapper demoMapper,
                          OutboxMapper outboxMapper,
                          DeliveryMapper deliveryMapper,
                          DeliveryService deliveryService,
                          AdmissionControl admission,
                          MeterRegistry meterRegistry,
                          HookFlowProperties properties) {
        this.registry = registry;
        this.simulator = simulator;
        this.receiver = receiver;
        this.demoMapper = demoMapper;
        this.outboxMapper = outboxMapper;
        this.deliveryMapper = deliveryMapper;
        this.deliveryService = deliveryService;
        this.admission = admission;
        this.meterRegistry = meterRegistry;
        this.properties = properties;
    }

    @GetMapping(value = "/state", produces = MediaType.APPLICATION_JSON_VALUE)
    public DemoDtos.State state() {
        List<DemoTenant> tenants = registry.tenants();
        List<Long> tenantIds = tenants.stream().map(DemoTenant::tenantId).toList();

        Map<Long, Map<String, Long>> counts = demoMapper.statusCounts(tenantIds).stream()
                .collect(Collectors.groupingBy(DemoMapper.StatusCount::tenantId,
                        Collectors.toMap(DemoMapper.StatusCount::status,
                                DemoMapper.StatusCount::total, Long::sum)));

        List<DemoDtos.Tenant> tenantViews = new ArrayList<>();
        for (DemoTenant tenant : tenants) {
            Map<String, Long> byStatus = counts.getOrDefault(tenant.tenantId(), Map.of());
            List<Long> samples = demoMapper.recentSucceededMicros(tenant.tenantId(),
                    LATENCY_SAMPLE_SIZE);
            long[] micros = samples.stream().mapToLong(Long::longValue).sorted().toArray();
            tenantViews.add(new DemoDtos.Tenant(
                    tenant.tenantId(), tenant.name(), tenant.slug(), tenant.endpointId(),
                    tenant.subscribedTypes(), tenant.maxInFlight(), tenant.weight(),
                    byStatus.getOrDefault("PENDING", 0L),
                    byStatus.getOrDefault("DELIVERING", 0L),
                    byStatus.getOrDefault("RETRY_WAIT", 0L),
                    byStatus.getOrDefault("SUCCEEDED", 0L),
                    byStatus.getOrDefault("DEAD", 0L),
                    byStatus.getOrDefault("CANCELED", 0L),
                    percentile(micros, 50), percentile(micros, 95), percentile(micros, 99)));
        }

        Map<Long, String> names = tenants.stream()
                .collect(Collectors.toMap(DemoTenant::tenantId, DemoTenant::name));
        List<DemoDtos.Delivery> deliveries = demoMapper
                .recentDeliveries(tenantIds, DELIVERY_FEED_SIZE).stream()
                .map(row -> {
                    // Latency only means something once the delivery is settled;
                    // a PENDING row would otherwise show a meaningless 0 ms.
                    boolean settled = "SUCCEEDED".equals(row.status())
                            || "DEAD".equals(row.status())
                            || "CANCELED".equals(row.status());
                    Long latencyMs = settled && row.latencyMicros() != null
                            ? row.latencyMicros() / 1000 : null;
                    return new DemoDtos.Delivery(
                            row.id(),
                            row.tenantId() == null ? 0 : row.tenantId(),
                            names.getOrDefault(row.tenantId(), "-"),
                            row.eventId() == null ? 0 : row.eventId(),
                            row.eventType(),
                            row.status(),
                            row.attemptCount() == null ? 0 : row.attemptCount(),
                            row.maxAttempts() == null ? 0 : row.maxAttempts(),
                            row.lastErrorClass(),
                            row.lastStatusCode(),
                            latencyMs);
                })
                .sorted(Comparator.comparingLong(DemoDtos.Delivery::id).reversed())
                .toList();

        long inFlight = deliveryMapper.countInFlightByTenant().stream()
                .filter(row -> tenantIds.contains(row.id()))
                .mapToLong(row -> row.inFlight())
                .sum();

        DemoDtos.Platform platform = new DemoDtos.Platform(
                outboxMapper.countUnpublished(),
                counter("hookflow.outbox.published"),
                counter("hookflow.outbox.publish_rejected"),
                counter("hookflow.ingest.shed", "scope", "tenant"),
                counter("hookflow.ingest.shed", "scope", "platform"),
                counter("hookflow.delivery.succeeded"),
                counter("hookflow.delivery.retry_scheduled"),
                counter("hookflow.delivery.dead"),
                inFlight,
                properties.backpressure() == null ? 0 : properties.backpressure().outboxHighWatermark(),
                properties.backpressure() == null ? 0 : properties.backpressure().tenantHighWatermark(),
                properties.fairness() != null && properties.fairness().enabled());

        DemoDtos.Simulator simulatorView = new DemoDtos.Simulator(
                simulator.running(), simulator.rate(), simulator.burstRemaining(), simulator.stats());

        DemoDtos.Receiver receiverView = new DemoDtos.Receiver(
                receiver.mode().name().toLowerCase(Locale.ROOT), receiver.delayMillis(),
                receiver.slowMillis(), receiver.receivedCount(), receiver.signatureOkCount(),
                receiver.signatureBadCount(), receiver.rejectCount(), receiver.recent());

        return new DemoDtos.State(Instant.now(), platform, simulatorView, receiverView,
                List.copyOf(tenantViews), deliveries, JAEGER_UI);
    }

    @PostMapping("/simulator")
    public Map<String, Object> simulator(@RequestParam(required = false) Boolean running,
                                         @RequestParam(required = false) Integer rate) {
        if (running != null) {
            simulator.running(running);
        }
        if (rate != null) {
            simulator.rate(rate);
        }
        return Map.of("running", simulator.running(), "ratePerSecond", simulator.rate());
    }

    @PostMapping("/receiver")
    public Map<String, Object> receiverMode(@RequestParam String mode,
                                            @RequestParam(required = false) Long delayMillis) {
        DemoReceiver.Mode parsed;
        try {
            parsed = DemoReceiver.Mode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("Unknown receiver mode: " + mode
                    + " (expected OK, ERROR, SLOW or REJECT)");
        }
        receiver.mode(parsed);
        if (delayMillis != null) {
            receiver.delayMillis(delayMillis);
        }
        return Map.of("mode", receiver.mode().name(),
                "delayMillis", receiver.delayMillis(),
                "slowMillis", receiver.slowMillis());
    }

    @PostMapping("/burst")
    public Map<String, Object> burst(@RequestParam long tenantId,
                                     @RequestParam(defaultValue = "200") int count,
                                     @RequestParam(defaultValue = "0") int perSecond) {
        DemoTenant tenant = registry.requireById(tenantId);
        long accepted = simulator.burst(tenant.tenantId(), count, perSecond);
        return Map.of("tenant", tenant.name(), "queued", accepted);
    }

    @PostMapping("/tenants/{tenantId}/budget")
    public DemoDtos.Tenant budget(@PathVariable long tenantId, @RequestParam int maxInFlight) {
        DemoTenant updated = registry.updateBudget(tenantId, maxInFlight);
        // The scheduler reads budgets from a snapshot; refresh so the effect of this
        // click is visible on the next tick instead of a second later.
        admission.refresh();
        DemoDtos.Tenant view = state().tenants().stream()
                .filter(t -> t.tenantId() == updated.tenantId())
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Tenant"));
        return view;
    }

    @PostMapping("/tenants/{tenantId}/event")
    public Map<String, Object> submitOne(@PathVariable long tenantId) {
        DemoTenant tenant = registry.requireById(tenantId);
        String orderId = simulator.submitOne(tenant.tenantId());
        return Map.of("tenant", tenant.name(), "orderId", orderId == null ? "" : orderId);
    }

    @PostMapping("/deliveries/{deliveryId}/replay")
    public Map<String, Object> replay(@PathVariable long deliveryId) {
        Delivery delivery = deliveryMapper.findById(deliveryId)
                .orElseThrow(() -> ApiException.notFound("Delivery"));
        Delivery replayed = deliveryService.replay(delivery.tenantId(), deliveryId);
        return Map.of("deliveryId", replayed.id(), "status", replayed.status().name());
    }

    /**
     * Wipes the demo tenants' events so a scenario can start from zero again.
     *
     * <p>Retried, and deliberately not one transaction. Stopping the simulator does
     * not stop the requests already in flight, so a delivery can be inserted between
     * "delete attempts" and "delete deliveries" and the foreign key rejects the
     * second statement. That is not a bug to design away — it is the same
     * bulk-delete-vs-row-insert interaction as {@code docs/failures/0007} — so the
     * reset simply runs again until the tables are actually empty.
     */
    @PostMapping("/reset")
    public Map<String, Object> reset() throws InterruptedException {
        simulator.running(false);
        receiver.mode(DemoReceiver.Mode.OK);
        List<Long> tenantIds = registry.tenants().stream().map(DemoTenant::tenantId).toList();

        int attempts = 0;
        int outbox = 0;
        int deliveries = 0;
        int events = 0;
        for (int round = 1; round <= 6; round++) {
            try {
                attempts = Math.max(attempts, demoMapper.deleteAttempts(tenantIds));
                outbox = Math.max(outbox, demoMapper.deleteOutbox(tenantIds));
                deliveries = Math.max(deliveries, demoMapper.deleteDeliveries(tenantIds));
                events = Math.max(events, demoMapper.deleteEvents(tenantIds));
            } catch (org.springframework.dao.DataAccessException racingIngest) {
                // An ingest committed between two deletes; a new round cleans up.
            }
            long remaining = demoMapper.statusCounts(tenantIds).stream()
                    .mapToLong(DemoMapper.StatusCount::total).sum();
            if (remaining == 0) {
                break;
            }
            Thread.sleep(150);
        }

        receiver.resetCounters();
        simulator.resetCounters();
        return Map.of("attempts", attempts, "outbox", outbox,
                "deliveries", deliveries, "events", events);
    }

    private long counter(String name, String... tags) {
        Counter counter = tags.length == 0
                ? meterRegistry.find(name).counter()
                : meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0L : (long) counter.count();
    }

    private static long percentile(long[] sortedMicros, double percentile) {
        if (sortedMicros.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sortedMicros.length) - 1;
        return sortedMicros[Math.min(sortedMicros.length - 1, Math.max(0, index))] / 1000;
    }
}

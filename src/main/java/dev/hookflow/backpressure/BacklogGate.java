package dev.hookflow.backpressure;

import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.domain.fairness.InFlight;
import dev.hookflow.persistence.OutboxMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Refuses events when accepting them would only make the backlog longer.
 *
 * <p>Two watermarks, deliberately different in scope and in status code:
 *
 * <ul>
 *   <li><b>Platform</b> — total un-published outbox rows above
 *       {@code outbox-high-watermark} means MySQL and the relay are behind. Answer
 *       {@code 503 + Retry-After}: nobody can be served, the caller should back off.</li>
 *   <li><b>Tenant</b> — this tenant's un-published rows above
 *       {@code tenant-high-watermark}. Answer {@code 429 + Retry-After}: <em>the
 *       platform is fine</em>, this customer is over its own budget. Without this
 *       split, one tenant's flood would 503 everybody else, which is exactly the
 *       isolation failure v0.3 removes.</li>
 * </ul>
 *
 * <p>The counts are cached for {@code check-interval-ms}: a {@code COUNT(*)} over
 * the un-published backlog on every single request would make the ingest path
 * O(backlog), which is the wrong shape for the one path that has to stay cheap
 * under load. The cache is single-flight, so a hundred concurrent requests cause
 * one count.
 *
 * <p>Note what is <b>not</b> counted: events already delivered. "How many events
 * has this tenant sent today" is a quota question; "how much work is outstanding"
 * is a capacity question, and only the second one predicts whether accepting more
 * will hurt.
 */
@Component
@Profile({"api", "all"})
public class BacklogGate {

    private static final Logger log = LoggerFactory.getLogger(BacklogGate.class);

    private final OutboxMapper outboxMapper;
    private final HookFlowProperties properties;
    private final MeterRegistry meterRegistry;

    private final AtomicBoolean refreshInProgress = new AtomicBoolean();
    /** Sampled by Micrometer; updated together with the cached snapshot. */
    private final java.util.concurrent.atomic.AtomicLong backlogGauge =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile long globalBacklog;
    private volatile Map<Long, Long> tenantBacklogs = Map.of();
    private volatile long lastRefreshNanos = Long.MIN_VALUE / 2;
    private volatile boolean shedding;
    private volatile boolean tenantShedding;

    public BacklogGate(OutboxMapper outboxMapper,
                       HookFlowProperties properties,
                       MeterRegistry meterRegistry) {
        this.outboxMapper = outboxMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge("hookflow.outbox.backlog", backlogGauge);
    }

    /**
     * @throws ApiException {@link ErrorCode#OVER_CAPACITY} when the platform is full,
     *                      {@link ErrorCode#TENANT_BACKLOG_EXCEEDED} when only this
     *                      tenant is over its own watermark
     */
    public void check(long tenantId) {
        check(tenantId, 0);
    }

    /**
     * The batch-aware form: the request will add {@code incomingEvents} rows to the
     * backlog if it is accepted, so it is judged against the watermark it would
     * create, not the one that exists before it.
     *
     * <p>Without this, a 500-event batch could jump a 10 000-row watermark by 5% per
     * request while every individual request looked "under the line" — the batch
     * endpoint would be a way around load shedding, which is the one thing shedding
     * must not have. {@code incomingEvents = 0} keeps the single-event behaviour
     * exactly as it was.
     *
     * @param incomingEvents how many backlog rows this request would add
     */
    public void check(long tenantId, int incomingEvents) {
        HookFlowProperties.Backpressure config = properties.backpressure();
        if (config == null) {
            return;
        }
        if (config.checkIntervalMs() <= 0) {
            return;
        }
        refreshIfStale(config);

        int incoming = Math.max(0, incomingEvents);
        long tenantBacklog = tenantBacklogs.getOrDefault(tenantId, 0L) + incoming;
        long platformBacklog = globalBacklog + incoming;
        String adds = incoming > 0 ? " (" + incoming + " in this request)" : "";

        if (config.outboxHighWatermark() > 0 && platformBacklog >= config.outboxHighWatermark()) {
            shed("platform", config);
            throw new ApiException(ErrorCode.OVER_CAPACITY,
                    "Platform backlog is " + globalBacklog + " events" + adds
                            + "; not accepting more right now",
                    config.retryAfterSeconds());
        }
        if (config.tenantHighWatermark() > 0 && tenantBacklog >= config.tenantHighWatermark()) {
            shed("tenant", config);
            throw new ApiException(ErrorCode.TENANT_BACKLOG_EXCEEDED,
                    "Your backlog is " + tenantBacklogs.getOrDefault(tenantId, 0L) + " undelivered events" + adds
                            + "; slow down until it drains below " + config.tenantHighWatermark(),
                    config.retryAfterSeconds());
        }
        clearSheddingIfRecovered();
    }

    /** Current platform backlog, for the actuator gauge and for tests. */
    public long globalBacklog() {
        return globalBacklog;
    }

    private void refreshIfStale(HookFlowProperties.Backpressure config) {
        long intervalNanos = config.checkIntervalMs() * 1_000_000L;
        if (System.nanoTime() - lastRefreshNanos < intervalNanos) {
            return;
        }
        if (!refreshInProgress.compareAndSet(false, true)) {
            return; // somebody else is counting; use the previous snapshot
        }
        try {
            globalBacklog = outboxMapper.countUnpublished();
            Map<Long, Long> perTenant = new HashMap<>();
            for (InFlight row : outboxMapper.countUnpublishedByTenants()) {
                if (row.id() != null) {
                    perTenant.put(row.id(), row.inFlight());
                }
            }
            tenantBacklogs = Map.copyOf(perTenant);
            lastRefreshNanos = System.nanoTime();
            backlogGauge.set(globalBacklog);
        } finally {
            refreshInProgress.set(false);
        }
    }

    private void shed(String scope, HookFlowProperties.Backpressure config) {
        meterRegistry.counter("hookflow.ingest.shed", "scope", scope).increment();
        if ("platform".equals(scope)) {
            if (!shedding) {
                shedding = true;
                log.warn("Shedding ingest: platform backlog {} >= watermark {}",
                        globalBacklog, config.outboxHighWatermark());
            }
        } else if (!tenantShedding) {
            tenantShedding = true;
            log.warn("Shedding ingest for one tenant: backlog >= watermark {}",
                    config.tenantHighWatermark());
        }
    }

    private void clearSheddingIfRecovered() {
        if (shedding) {
            shedding = false;
            log.info("Platform backlog recovered; accepting events again (backlog={})", globalBacklog);
        }
        if (tenantShedding) {
            tenantShedding = false;
            log.info("Tenant backlog recovered");
        }
    }
}

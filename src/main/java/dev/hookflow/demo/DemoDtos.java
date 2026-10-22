package dev.hookflow.demo;

import java.time.Instant;
import java.util.List;

/**
 * The console's wire format. One poll returns everything the page draws, so the
 * browser never has to stitch a consistent picture out of several requests that were
 * taken at different moments.
 */
public final class DemoDtos {

    private DemoDtos() {
    }

    public record State(Instant now,
                        Platform platform,
                        Simulator simulator,
                        Receiver receiver,
                        List<Tenant> tenants,
                        List<Delivery> deliveries,
                        String jaegerUrl) {
    }

    /** Platform-wide numbers: the ones that answer "is it keeping up". */
    public record Platform(long outboxBacklog,
                           long published,
                           long publishRejected,
                           long shedTenant,
                           long shedPlatform,
                           long succeeded,
                           long retryScheduled,
                           long dead,
                           long inFlight,
                           long outboxHighWatermark,
                           long tenantHighWatermark,
                           boolean fairnessEnabled) {
    }

    public record Simulator(boolean running,
                            int ratePerSecond,
                            long burstRemaining,
                            List<BusinessSimulator.StreamStats> streams) {
    }

    public record Receiver(String mode,
                           long delayMillis,
                           long slowMillis,
                           long received,
                           long signatureOk,
                           long signatureBad,
                           long rejects,
                           List<DemoReceiver.Received> recent) {
    }

    /** One tenant's side of the story, including its own latency percentiles. */
    public record Tenant(long tenantId,
                         String name,
                         String slug,
                         long endpointId,
                         String subscribedTypes,
                         int maxInFlight,
                         int weight,
                         long pending,
                         long delivering,
                         long retryWait,
                         long succeeded,
                         long dead,
                         long canceled,
                         long p50Ms,
                         long p95Ms,
                         long p99Ms) {
    }

    /** One row of the live delivery feed. */
    public record Delivery(long id,
                           long tenantId,
                           String tenantName,
                           long eventId,
                           String eventType,
                           String status,
                           int attemptCount,
                           int maxAttempts,
                           String lastErrorClass,
                           Integer lastStatusCode,
                           Long latencyMs) {
    }
}

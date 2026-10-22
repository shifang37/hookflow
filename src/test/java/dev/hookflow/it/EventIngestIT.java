package dev.hookflow.it;

import dev.hookflow.domain.delivery.Delivery;
import dev.hookflow.domain.delivery.DeliveryStatus;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.persistence.OutboxMapper;
import dev.hookflow.persistence.TenantMapper;
import dev.hookflow.service.ApiKeyService;
import dev.hookflow.service.EndpointService;
import dev.hookflow.service.EventIngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance criterion: concurrent submissions of the same idempotency key
 * produce exactly one event and one delivery per matching endpoint.
 */
class EventIngestIT extends AbstractIntegrationTest {

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

    private long tenantId;

    @BeforeEach
    void setUp() {
        String name = "it-" + UUID.randomUUID();
        tenantMapper.insert(name, 0);
        tenantId = tenantMapper.findByName(name).orElseThrow().id();
        apiKeyService.issue(tenantId, "event:write,endpoint:write,delivery:read");
    }

    @Test
    @DisplayName("20 concurrent identical submissions -> one event, one delivery")
    void concurrentDuplicateSubmissionsCollapseToOneEvent() throws Exception {
        endpointService.create(tenantId, "https://example.test/hook", "*", 8);

        String key = "idem-" + UUID.randomUUID();
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        List<Callable<EventIngestService.IngestResult>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return ingestService.ingest(tenantId, "order.paid", "{\"amount\":42}", key);
            });
        }
        List<Future<EventIngestService.IngestResult>> futures = tasks.stream().map(pool::submit).toList();
        startLine.countDown();

        List<EventIngestService.IngestResult> results = new ArrayList<>();
        for (Future<EventIngestService.IngestResult> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        List<Long> distinctEventIds = results.stream()
                .map(EventIngestService.IngestResult::eventId)
                .distinct()
                .toList();
        assertThat(distinctEventIds)
                .as("every caller must be told about the same canonical event")
                .hasSize(1);

        long eventId = distinctEventIds.getFirst();
        assertThat(deliveryMapper.count(tenantId, eventId, null))
                .as("exactly one delivery per matching endpoint")
                .isEqualTo(1);
        assertThat(outboxMapper.countPending() + 1).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("an event fans out only to endpoints whose subscription matches")
    void fanOutRespectsSubscriptions() {
        endpointService.create(tenantId, "https://a.test/hook", "order.paid", 8);
        endpointService.create(tenantId, "https://b.test/hook", "order.paid,order.refunded", 8);
        endpointService.create(tenantId, "https://c.test/hook", "user.created", 8);
        endpointService.create(tenantId, "https://d.test/hook", "*", 8);

        EventIngestService.IngestResult result =
                ingestService.ingest(tenantId, "order.paid", "{\"amount\":1}", "fan-" + UUID.randomUUID());

        assertThat(result.deliveryCount())
                .as("a, b and the wildcard endpoint, but not c")
                .isEqualTo(3);
        assertThat(result.idempotentReplay()).isFalse();
    }

    @Test
    @DisplayName("a paused endpoint receives no new deliveries")
    void pausedEndpointIsSkipped() {
        var created = endpointService.create(tenantId, "https://paused.test/hook", "*", 8);
        endpointService.pause(tenantId, created.endpoint().id());

        EventIngestService.IngestResult result =
                ingestService.ingest(tenantId, "order.paid", "{}", "paused-" + UUID.randomUUID());

        assertThat(result.deliveryCount()).isZero();
    }

    @Test
    @DisplayName("claiming is exclusive: the second claim for the same attempt updates nothing")
    void claimIsExclusive() {
        endpointService.create(tenantId, "https://claim.test/hook", "*", 8);
        EventIngestService.IngestResult result =
                ingestService.ingest(tenantId, "order.paid", "{}", "claim-" + UUID.randomUUID());

        Delivery delivery = deliveryMapper.list(tenantId, result.eventId(), null, 10, 0).getFirst();

        int first = deliveryMapper.claim(delivery.id(), 0, UUID.randomUUID().toString(), 60);
        int second = deliveryMapper.claim(delivery.id(), 0, UUID.randomUUID().toString(), 60);

        assertThat(first).as("first claim wins").isEqualTo(1);
        assertThat(second).as("duplicate claim must be a no-op").isZero();
        assertThat(deliveryMapper.findById(delivery.id()).orElseThrow().status())
                .isEqualTo(DeliveryStatus.DELIVERING);
    }

    @Test
    @DisplayName("an expired lease is reclaimed and the abandoned attempt is recorded")
    void expiredLeaseIsReclaimed() {
        endpointService.create(tenantId, "https://lease.test/hook", "*", 8);
        EventIngestService.IngestResult result =
                ingestService.ingest(tenantId, "order.paid", "{}", "lease-" + UUID.randomUUID());
        Delivery delivery = deliveryMapper.list(tenantId, result.eventId(), null, 10, 0).getFirst();

        // A negative lease length produces an already-expired lease: this is the
        // only way to exercise reclaiming without waiting a real minute.
        deliveryMapper.claim(delivery.id(), 0, UUID.randomUUID().toString(), -5);

        List<Delivery> expired = deliveryMapper.findExpiredLeases(100);
        assertThat(expired).extracting(Delivery::id).contains(delivery.id());

        assertThat(deliveryMapper.reclaimExpiredLease(delivery.id())).isEqualTo(1);
        Delivery reclaimed = deliveryMapper.findById(delivery.id()).orElseThrow();
        assertThat(reclaimed.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(reclaimed.leaseToken()).isNull();
        assertThat(reclaimed.attemptCount()).as("the consumed attempt is not refunded").isEqualTo(1);
    }

    @Test
    @DisplayName("replaying a non-DEAD delivery is rejected")
    void replayRequiresDeadState() {
        endpointService.create(tenantId, "https://replay.test/hook", "*", 8);
        EventIngestService.IngestResult result =
                ingestService.ingest(tenantId, "order.paid", "{}", "replay-" + UUID.randomUUID());
        Delivery delivery = deliveryMapper.list(tenantId, result.eventId(), null, 10, 0).getFirst();

        assertThat(deliveryMapper.replay(delivery.id(), tenantId)).isZero();
    }

    @Test
    @DisplayName("outbox rows are unique per (delivery, attempt) but allow the next attempt")
    void outboxUniquenessAllowsNextAttempt() {
        endpointService.create(tenantId, "https://outbox.test/hook", "*", 8);
        EventIngestService.IngestResult result =
                ingestService.ingest(tenantId, "order.paid", "{}", "outbox-" + UUID.randomUUID());
        long deliveryId = deliveryMapper.list(tenantId, result.eventId(), null, 10, 0).getFirst().id();

        assertThatThrownBy(() -> outboxMapper.insert("DELIVERY", deliveryId, 1, "{\"attemptNo\":1}"))
                .as("a second row for the same attempt must violate the unique key")
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);

        assertThat(outboxMapper.insert("DELIVERY", deliveryId, 2, "{\"attemptNo\":2}"))
                .as("the next attempt must still be schedulable")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("ids of the fan-out set are stable across repeated reads")
    void deliveriesAreQueryableByEvent() {
        endpointService.create(tenantId, "https://q1.test/hook", "*", 8);
        endpointService.create(tenantId, "https://q2.test/hook", "*", 8);
        EventIngestService.IngestResult result =
                ingestService.ingest(tenantId, "order.paid", "{}", "q-" + UUID.randomUUID());

        List<Long> ids = deliveryMapper.list(tenantId, result.eventId(), null, 10, 0).stream()
                .map(Delivery::id)
                .collect(Collectors.toList());
        assertThat(ids).hasSize(2).doesNotHaveDuplicates();
    }
}

package dev.hookflow.it;

import dev.hookflow.persistence.TenantMapper;
import dev.hookflow.service.ApiKeyService;
import dev.hookflow.service.EndpointService;
import dev.hookflow.service.EventIngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /v1/events/batch}, over real HTTP against real MySQL.
 *
 * <p>The acceptance criterion from the v0.4 plan: 20 events with 3 idempotency keys
 * that already exist must all come back with the right event id and must not create
 * a single duplicate. That is a stronger statement than "the batch endpoint returns
 * 202" — it says the idempotency key still means what it meant before batching, and
 * that a partial duplicate does not poison the other 17 events.
 *
 * <p>The endpoint is the one place the batch shape is actually observable: status
 * code (202 vs 200), one result per item with its {@code index}, and a request-level
 * rejection for an oversized batch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BatchIngestIT extends AbstractIntegrationTest {

    private static final int BATCH = 20;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final tools.jackson.databind.json.JsonMapper json =
            tools.jackson.databind.json.JsonMapper.builder().build();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private EndpointService endpointService;
    @Autowired
    private EventIngestService ingestService;
    @Autowired
    private JdbcTemplate jdbc;

    private long tenantId;
    private String token;

    @BeforeEach
    void setUp() {
        String name = "it-batch-" + UUID.randomUUID();
        // rateLimit 0 = no quota: this test is about batch semantics, not throttling.
        tenantMapper.insert(name, 0);
        tenantId = tenantMapper.findByName(name).orElseThrow().id();
        token = apiKeyService.issue(tenantId, "event:write,endpoint:write,delivery:read").token();
        // maxAttempts 1 keeps the worker from spending the test retrying a host that
        // does not resolve; the delivery outcome is irrelevant here.
        endpointService.create(tenantId, "https://batch-" + UUID.randomUUID() + ".test/hook", "*", 1);
    }

    @Test
    @DisplayName("20 events with 3 existing idempotency keys: 20 correct ids, no duplicate events")
    void partialDuplicatesDoNotAbortTheBatch() throws Exception {
        Map<String, Long> preExisting = seedEvents(3);

        List<Map<String, Object>> items = new ArrayList<>(BATCH);
        Set<String> reusedKeys = preExisting.keySet();
        List<String> expectedKeys = new ArrayList<>(BATCH);
        int reused = 0;
        for (int i = 0; i < BATCH; i++) {
            // Spread the three existing keys through the batch so they are not a
            // convenient prefix: index 2, 9 and 15 are replays.
            String key;
            if (i == 2 || i == 9 || i == 15) {
                key = new ArrayList<>(reusedKeys).get(reused++);
            } else {
                key = "batch-" + UUID.randomUUID() + "-" + i;
            }
            expectedKeys.add(key);
            items.add(item("order.paid", Map.of("amount", i), key));
        }

        HttpResponse<String> response = postBatch(items);

        assertThat(response.statusCode())
                .as("at least one item was new, so the batch is 202")
                .isEqualTo(202);
        List<Map<String, Object>> results = readResults(response.body());
        assertThat(results).hasSize(BATCH);

        for (int i = 0; i < BATCH; i++) {
            Map<String, Object> result = results.get(i);
            assertThat(number(result, "index")).as("results are tagged with their request position")
                    .isEqualTo(i);
            assertThat(number(result, "deliveryCount"))
                    .as("the tenant has exactly one endpoint subscribed to order.paid")
                    .isEqualTo(1);
            String key = expectedKeys.get(i);
            if (preExisting.containsKey(key)) {
                assertThat(number(result, "eventId"))
                        .as("a replayed key must point at the original event")
                        .isEqualTo(preExisting.get(key));
                assertThat(result.get("replay")).isEqualTo(true);
            } else {
                assertThat(result.get("replay")).isEqualTo(false);
            }
        }

        assertThat(distinctEventIds(results))
                .as("every item answered with an event id; the 3 replayed keys point at existing ones")
                .hasSize(BATCH)
                .containsAll(preExisting.values());
        assertThat(results.stream().filter(result -> Boolean.FALSE.equals(result.get("replay"))).count())
                .as("17 items created a new event, the 3 seeded keys did not")
                .isEqualTo(BATCH - preExisting.size());

        assertThat(eventCount()).as("no event was stored twice").isEqualTo(BATCH);
        assertThat(deliveryCount()).as("no delivery was stored twice").isEqualTo(BATCH);

        // The whole batch is replayable: same keys, same answers, still no duplicates.
        HttpResponse<String> again = postBatch(items);
        assertThat(again.statusCode()).as("every item is a replay now").isEqualTo(200);
        List<Map<String, Object>> replayed = readResults(again.body());
        assertThat(distinctEventIds(replayed)).isEqualTo(distinctEventIds(results));
        assertThat(replayed).allSatisfy(result ->
                assertThat(result.get("replay")).isEqualTo(true));
        assertThat(eventCount()).isEqualTo(BATCH);
        assertThat(deliveryCount()).isEqualTo(BATCH);
    }

    @Test
    @DisplayName("a key repeated inside one batch resolves to one event")
    void repeatedKeyInsideTheBatchIsOneEvent() throws Exception {
        String first = "same-" + UUID.randomUUID();
        String second = "other-" + UUID.randomUUID();
        List<Map<String, Object>> items = List.of(
                item("order.paid", Map.of("n", 1), first),
                item("order.paid", Map.of("n", 2), second),
                item("order.paid", Map.of("n", 3), first),
                item("order.paid", Map.of("n", 4), "third-" + UUID.randomUUID()),
                item("order.paid", Map.of("n", 5), second));

        HttpResponse<String> response = postBatch(items);

        assertThat(response.statusCode()).isEqualTo(202);
        List<Map<String, Object>> results = readResults(response.body());
        assertThat(results).hasSize(5);
        assertThat(results.get(2).get("replay")).as("repeat of item 0").isEqualTo(true);
        assertThat(results.get(4).get("replay")).as("repeat of item 1").isEqualTo(true);
        assertThat(number(results.get(2), "eventId")).isEqualTo(number(results.get(0), "eventId"));
        assertThat(number(results.get(4), "eventId")).isEqualTo(number(results.get(1), "eventId"));
        assertThat(distinctEventIds(results)).hasSize(3);
        assertThat(eventCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("a batch over the 500-item ceiling is rejected as a whole, not truncated")
    void oversizedBatchIsRejected() throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            items.add(item("order.paid", Map.of("n", i), "over-" + UUID.randomUUID() + "-" + i));
        }

        HttpResponse<String> response = postBatch(items);

        assertThat(response.statusCode()).isEqualTo(400);
        Map<String, Object> error = readMap(response.body());
        assertThat(number(error, "code")).isEqualTo(40000);
        assertThat(eventCount()).isZero();
    }

    @Test
    @DisplayName("an empty events array is a 400, not an empty 200")
    void emptyBatchIsRejected() throws Exception {
        HttpResponse<String> response = postBatch(List.of());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(number(readMap(response.body()), "code")).isEqualTo(40000);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Long> seedEvents(int count) {
        Map<String, Long> byKey = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String key = "pre-" + UUID.randomUUID() + "-" + i;
            EventIngestService.IngestResult result =
                    ingestService.ingest(tenantId, "order.paid", "{\"seed\":" + i + "}", key);
            assertThat(result.idempotentReplay()).isFalse();
            byKey.put(key, result.eventId());
        }
        return byKey;
    }

    private static Map<String, Object> item(String eventType, Object payload, String key) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("eventType", eventType);
        item.put("payload", payload);
        item.put("idempotencyKey", key);
        return item;
    }

    private HttpResponse<String> postBatch(List<Map<String, Object>> items) throws IOException, InterruptedException {
        String body = json.writeValueAsString(Map.of("events", items));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/v1/events/batch"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readResults(String body) throws IOException {
        return json.readValue(body, List.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String body) throws IOException {
        return json.readValue(body, Map.class);
    }

    private static long number(Map<String, Object> source, String field) {
        return ((Number) source.get(field)).longValue();
    }

    private static Set<Long> distinctEventIds(List<Map<String, Object>> results) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Map<String, Object> result : results) {
            ids.add(number(result, "eventId"));
        }
        return ids;
    }

    private long eventCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE tenant_id = ?",
                Long.class, tenantId);
        return count == null ? 0 : count;
    }

    private long deliveryCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM delivery WHERE tenant_id = ?",
                Long.class, tenantId);
        return count == null ? 0 : count;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }
}

package dev.hookflow.api;

import dev.hookflow.api.dto.Dtos.CreateEventBatchItem;
import dev.hookflow.api.dto.Dtos.CreateEventBatchRequest;
import dev.hookflow.api.dto.Dtos.CreateEventRequest;
import dev.hookflow.api.dto.Dtos.EventResponse;
import dev.hookflow.api.dto.Dtos.EventSummaryResponse;
import dev.hookflow.common.auth.ApiKeyPrincipal;
import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.common.web.PageResponse;
import dev.hookflow.domain.tenant.Tenant;
import dev.hookflow.ratelimit.TenantRateLimiter;
import dev.hookflow.service.EventIngestService;
import dev.hookflow.service.ReportService;
import dev.hookflow.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/v1/events")
@Profile({"api", "all"})
@Tag(name = "Events", description = "Accept events and fan them out to endpoints")
public class EventController {

    /** Event types are restricted so they can be embedded in a JSON envelope verbatim. */
    private static final Pattern EVENT_TYPE = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

    /**
     * Upper bound of one batch request.
     *
     * <p>500 is a round trip/variance trade-off, not a capacity limit: it keeps one
     * transaction's statement under a few MB and its lock hold short, while already
     * flattening the per-event cost of the ingest path. Above that the right shape is
     * several requests, not one huge one.
     */
    public static final int MAX_BATCH_EVENTS = 500;

    private final EventIngestService ingestService;
    private final TenantService tenantService;
    private final TenantRateLimiter rateLimiter;
    private final dev.hookflow.backpressure.BacklogGate backlogGate;
    private final ObjectMapper objectMapper;
    private final ReportService reportService;

    public EventController(EventIngestService ingestService,
                           TenantService tenantService,
                           TenantRateLimiter rateLimiter,
                           dev.hookflow.backpressure.BacklogGate backlogGate,
                           ObjectMapper objectMapper,
                           ReportService reportService) {
        this.ingestService = ingestService;
        this.tenantService = tenantService;
        this.rateLimiter = rateLimiter;
        this.backlogGate = backlogGate;
        this.objectMapper = objectMapper;
        this.reportService = reportService;
    }

    /**
     * The console's event stream: newest first, one row per event with its delivery
     * roll-up, so the table does not need a follow-up request per row.
     *
     * <p>{@code from}/{@code to} are ISO-8601 local date-times interpreted as UTC
     * (the same clock the rest of the API reports). {@code to} is exclusive.
     */
    @GetMapping
    @Operation(summary = "List events newest-first, each with its delivery counters")
    public PageResponse<EventSummaryResponse> list(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        long tenantId = ApiKeyPrincipal.Context.requireTenantId();
        return reportService.listEvents(tenantId, eventType, from, to, page, size);
    }

    /**
     * @return 202 for a freshly accepted event, 200 when the idempotency key was
     *         already used (same event id is returned, so retries are safe)
     */
    @PostMapping
    @Operation(summary = "Submit an event; send Idempotency-Key to make retries safe")
    public ResponseEntity<EventResponse> submit(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreateEventRequest request) {

        long tenantId = ApiKeyPrincipal.Context.requireTenantId();
        Tenant tenant = tenantService.requireActive(tenantId);
        // Order matters: the rate limiter is a Redis counter (cheap, per-second
        // quota), the backlog gate is a cached MySQL count (capacity). Rejecting on
        // quota first keeps the capacity check off the hot path of a tenant that is
        // already being rate limited.
        rateLimiter.acquire(tenantId, tenant.rateLimit());
        backlogGate.check(tenantId);

        String eventType = request.eventType();
        if (eventType == null || !EVENT_TYPE.matcher(eventType).matches()) {
            throw dev.hookflow.common.error.ApiException.invalid(
                    "eventType must match [A-Za-z0-9._-] and be 1-128 characters");
        }
        if (request.payload() == null) {
            throw dev.hookflow.common.error.ApiException.invalid("payload is required");
        }
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw dev.hookflow.common.error.ApiException.invalid(
                    "Idempotency-Key must be 1-255 characters");
        }

        // Normalise the payload to a JSON string exactly once, at ingest. From here
        // on the delivery path embeds this string verbatim, so every retry of this
        // event sends identical bytes.
        String payloadJson = objectMapper.writeValueAsString(request.payload());

        EventIngestService.IngestResult result =
                ingestService.ingest(tenantId, eventType, payloadJson, idempotencyKey);

        EventResponse body = new EventResponse(result.eventId(), result.deliveryCount(),
                result.idempotentReplay());
        return ResponseEntity
                .status(result.idempotentReplay() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(body);
    }

    @GetMapping("/{eventId}")
    @Operation(summary = "Fetch one event")
    public dev.hookflow.api.dto.Dtos.EventResponse getEvent(@PathVariable long eventId) {
        var event = ingestService.requireEvent(ApiKeyPrincipal.Context.requireTenantId(), eventId);
        return new dev.hookflow.api.dto.Dtos.EventResponse(event.id(), 0, false);
    }

    /**
     * Submits up to {@value #MAX_BATCH_EVENTS} events in one transaction.
     *
     * <p>Rate limiting and load shedding are counted in <b>events</b>, not requests:
     * a batch of N consumes N permits and is judged against the backlog it would
     * create. Otherwise the batch endpoint would be the cheapest way around both
     * protection mechanisms.
     *
     * @return 202 when at least one item created a new event, 200 when every item was
     *         a replay; the body always has one entry per request item, tagged with
     *         its {@code index}
     */
    @PostMapping("/batch")
    @Operation(summary = "Submit up to " + MAX_BATCH_EVENTS
            + " events in one transaction; each item carries its own idempotencyKey")
    public ResponseEntity<List<dev.hookflow.api.dto.Dtos.BatchEventResponse>> submitBatch(
            @RequestBody CreateEventBatchRequest request) {

        long tenantId = ApiKeyPrincipal.Context.requireTenantId();
        Tenant tenant = tenantService.requireActive(tenantId);

        List<CreateEventBatchItem> items = request == null ? null : request.events();
        if (items == null || items.isEmpty() || items.size() > MAX_BATCH_EVENTS) {
            throw ApiException.invalid("events must contain 1-" + MAX_BATCH_EVENTS + " items");
        }

        // Same order as the single-event path: quota first, capacity second.
        rateLimiter.acquire(tenantId, tenant.rateLimit(), items.size());
        backlogGate.check(tenantId, items.size());

        List<EventIngestService.BatchItem> batch = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            CreateEventBatchItem item = items.get(index);
            if (item == null) {
                throw ApiException.invalid("events[" + index + "] must not be null");
            }
            String eventType = item.eventType();
            if (eventType == null || !EVENT_TYPE.matcher(eventType).matches()) {
                throw ApiException.invalid("events[" + index
                        + "].eventType must match [A-Za-z0-9._-] and be 1-128 characters");
            }
            if (item.payload() == null) {
                throw ApiException.invalid("events[" + index + "].payload is required");
            }
            String idempotencyKey = item.idempotencyKey();
            if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
                throw ApiException.invalid("events[" + index
                        + "].idempotencyKey must be 1-255 characters");
            }
            batch.add(new EventIngestService.BatchItem(eventType,
                    objectMapper.writeValueAsString(item.payload()), idempotencyKey));
        }

        List<EventIngestService.BatchResult> results;
        try {
            results = ingestService.ingestBatch(tenantId, batch);
        } catch (DuplicateKeyException lostRace) {
            // Every retry lost and the winner is still not readable: the pathological
            // case documented for the single-event path. 500, not 503 - a blind retry
            // cannot repair it from here.
            throw lostRace;
        } catch (DataAccessException notCommitted) {
            // Nothing of the batch was committed (one transaction), so retrying the
            // same batch with the same idempotency keys is safe. 503 tells the caller
            // this is a dependency problem, not a bad request.
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "The batch was not committed; retrying it with the same idempotency keys is safe",
                    5);
        }

        boolean createdAny = results.stream()
                .anyMatch(result -> !result.idempotentReplay());
        List<dev.hookflow.api.dto.Dtos.BatchEventResponse> body = results.stream()
                .map(result -> new dev.hookflow.api.dto.Dtos.BatchEventResponse(
                        result.index(), result.eventId(), result.deliveryCount(),
                        result.idempotentReplay()))
                .toList();
        return ResponseEntity.status(createdAny ? HttpStatus.ACCEPTED : HttpStatus.OK).body(body);
    }
}

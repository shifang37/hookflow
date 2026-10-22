package dev.hookflow.api;

import dev.hookflow.api.dto.Dtos.CreateEventRequest;
import dev.hookflow.api.dto.Dtos.EventResponse;
import dev.hookflow.common.auth.ApiKeyPrincipal;
import dev.hookflow.domain.tenant.Tenant;
import dev.hookflow.ratelimit.TenantRateLimiter;
import dev.hookflow.service.EventIngestService;
import dev.hookflow.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.regex.Pattern;

@RestController
@RequestMapping("/v1/events")
@Profile({"api", "all"})
@Tag(name = "Events", description = "Accept events and fan them out to endpoints")
public class EventController {

    /** Event types are restricted so they can be embedded in a JSON envelope verbatim. */
    private static final Pattern EVENT_TYPE = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

    private final EventIngestService ingestService;
    private final TenantService tenantService;
    private final TenantRateLimiter rateLimiter;
    private final dev.hookflow.backpressure.BacklogGate backlogGate;
    private final ObjectMapper objectMapper;

    public EventController(EventIngestService ingestService,
                           TenantService tenantService,
                           TenantRateLimiter rateLimiter,
                           dev.hookflow.backpressure.BacklogGate backlogGate,
                           ObjectMapper objectMapper) {
        this.ingestService = ingestService;
        this.tenantService = tenantService;
        this.rateLimiter = rateLimiter;
        this.backlogGate = backlogGate;
        this.objectMapper = objectMapper;
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
}

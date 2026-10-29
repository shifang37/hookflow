package dev.hookflow.api;

import dev.hookflow.api.dto.Dtos.AttemptResponse;
import dev.hookflow.api.dto.Dtos.BatchReplayRequest;
import dev.hookflow.api.dto.Dtos.BatchReplayResponse;
import dev.hookflow.api.dto.Dtos.DeliveryDetailResponse;
import dev.hookflow.api.dto.Dtos.DeliveryResponse;
import dev.hookflow.api.dto.Dtos.ReplayResultResponse;
import dev.hookflow.common.auth.ApiKeyPrincipal;
import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.web.PageResponse;
import dev.hookflow.domain.delivery.Delivery;
import dev.hookflow.service.DeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;

@RestController
@RequestMapping("/v1/deliveries")
@Profile({"api", "all"})
@Tag(name = "Deliveries", description = "Inspect delivery state and attempt history, replay failures")
public class DeliveryController {

    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @GetMapping
    @Operation(summary = "List deliveries, optionally filtered by event, endpoint, status or time window")
    public PageResponse<DeliveryResponse> list(@RequestParam(required = false) Long eventId,
                                               @RequestParam(required = false) Long endpointId,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false)
                                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                               LocalDateTime from,
                                               @RequestParam(required = false)
                                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                               LocalDateTime to,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        long tenantId = ApiKeyPrincipal.Context.requireTenantId();
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        DeliveryService.Page result = deliveryService.list(
                tenantId, eventId, endpointId, status, from, to, safePage, safeSize);
        List<DeliveryResponse> items = result.items().stream().map(DeliveryController::toResponse).toList();
        return PageResponse.of(items, safePage, safeSize, result.total());
    }

    @GetMapping("/{deliveryId}")
    @Operation(summary = "Fetch one delivery with its attempt history")
    public DeliveryDetailResponse get(@PathVariable long deliveryId) {
        long tenantId = ApiKeyPrincipal.Context.requireTenantId();
        Delivery delivery = deliveryService.require(tenantId, deliveryId);
        List<AttemptResponse> attempts = deliveryService.attempts(tenantId, deliveryId).stream()
                .map(attempt -> new AttemptResponse(attempt.attemptNo(), attempt.status(),
                        attempt.statusCode(), attempt.errorClass(), attempt.durationMs(),
                        attempt.responsePreview(), attempt.startedAt(), attempt.finishedAt()))
                .toList();
        return new DeliveryDetailResponse(toResponse(delivery), attempts);
    }

    @PostMapping("/{deliveryId}/replay")
    @Operation(summary = "Re-queue a DEAD delivery for one more attempt")
    public DeliveryResponse replay(@PathVariable long deliveryId) {
        return toResponse(deliveryService.replay(ApiKeyPrincipal.Context.requireTenantId(), deliveryId));
    }

    /**
     * Batch replay: one line per requested id, in request order.
     *
     * <p><b>200 even when items are rejected.</b> The contract is a partial result, and the
     * client needs to know which ids succeeded; a 4xx would throw that answer away, and 207
     * would make every caller special-case a status code they rarely meet. The three
     * request-level mistakes — empty, more than {@value DeliveryService#MAX_BATCH_REPLAY},
     * duplicates — are 400s, because they describe the request rather than any delivery.
     */
    @PostMapping("/replay")
    @Operation(summary = "Replay up to " + DeliveryService.MAX_BATCH_REPLAY
            + " DEAD deliveries, reporting each id separately")
    public BatchReplayResponse replayBatch(@RequestBody BatchReplayRequest request) {
        List<Long> deliveryIds = request == null ? null : request.deliveryIds();
        if (deliveryIds == null || deliveryIds.isEmpty()
                || deliveryIds.size() > DeliveryService.MAX_BATCH_REPLAY) {
            throw ApiException.invalid("deliveryIds must contain 1-"
                    + DeliveryService.MAX_BATCH_REPLAY + " ids");
        }
        // Stream rather than contains(null): List.of(...) rejects null in contains() with an
        // NPE, and a validation path must not throw for the input it is validating.
        if (deliveryIds.stream().anyMatch(id -> id == null)) {
            throw ApiException.invalid("deliveryIds must not contain null");
        }
        if (new HashSet<>(deliveryIds).size() != deliveryIds.size()) {
            throw ApiException.invalid("deliveryIds must not contain duplicates");
        }

        long tenantId = ApiKeyPrincipal.Context.requireTenantId();
        List<DeliveryService.ReplayOutcome> outcomes = deliveryService.replayBatch(tenantId, deliveryIds);
        List<ReplayResultResponse> results = outcomes.stream()
                .map(outcome -> new ReplayResultResponse(outcome.deliveryId(), outcome.replayed(),
                        outcome.status(),
                        outcome.rejection() == null ? null : outcome.rejection().code(),
                        outcome.message()))
                .toList();
        int replayed = (int) outcomes.stream().filter(DeliveryService.ReplayOutcome::replayed).count();
        return new BatchReplayResponse(replayed, results.size() - replayed, results);
    }

    static DeliveryResponse toResponse(Delivery delivery) {
        return new DeliveryResponse(delivery.id(), delivery.eventId(), delivery.endpointId(),
                delivery.status().name(), delivery.attemptCount(), delivery.maxAttempts(),
                delivery.nextAttemptAt(), delivery.lastErrorClass(), delivery.lastStatusCode(),
                delivery.createdAt(), delivery.updatedAt());
    }
}

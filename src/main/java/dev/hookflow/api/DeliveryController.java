package dev.hookflow.api;

import dev.hookflow.api.dto.Dtos.AttemptResponse;
import dev.hookflow.api.dto.Dtos.DeliveryDetailResponse;
import dev.hookflow.api.dto.Dtos.DeliveryResponse;
import dev.hookflow.common.auth.ApiKeyPrincipal;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
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

    static DeliveryResponse toResponse(Delivery delivery) {
        return new DeliveryResponse(delivery.id(), delivery.eventId(), delivery.endpointId(),
                delivery.status().name(), delivery.attemptCount(), delivery.maxAttempts(),
                delivery.nextAttemptAt(), delivery.lastErrorClass(), delivery.lastStatusCode(),
                delivery.createdAt(), delivery.updatedAt());
    }
}

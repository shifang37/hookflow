package dev.hookflow.api;

import dev.hookflow.api.dto.Dtos.CreateEndpointRequest;
import dev.hookflow.api.dto.Dtos.EndpointCreatedResponse;
import dev.hookflow.api.dto.Dtos.EndpointResponse;
import dev.hookflow.common.auth.ApiKeyPrincipal;
import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.web.PageResponse;
import dev.hookflow.domain.endpoint.Endpoint;
import dev.hookflow.service.EndpointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/endpoints")
@Profile({"api", "all"})
@Tag(name = "Endpoints", description = "Customer callback targets")
public class EndpointController {

    private static final String SCOPE = "endpoint:write";

    private final EndpointService endpointService;

    public EndpointController(EndpointService endpointService) {
        this.endpointService = endpointService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an endpoint and receive its signing secret once")
    public EndpointCreatedResponse create(@RequestBody CreateEndpointRequest request) {
        requireScope(SCOPE);
        EndpointService.CreatedEndpoint created = endpointService.create(
                ApiKeyPrincipal.Context.requireTenantId(),
                request.url(), request.subscribedTypes(), request.maxAttempts(),
                request.maxConcurrency());
        Endpoint endpoint = created.endpoint();
        return new EndpointCreatedResponse(endpoint.id(), endpoint.url(), endpoint.subscribedTypes(),
                endpoint.maxAttempts(), endpoint.maxConcurrency(), endpoint.status(),
                created.signingSecret());
    }

    @GetMapping
    @Operation(summary = "List endpoints for the calling tenant")
    public PageResponse<EndpointResponse> list(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        long tenantId = ApiKeyPrincipal.Context.requireTenantId();
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        List<EndpointResponse> items = endpointService.list(tenantId, safePage, safeSize).stream()
                .map(EndpointController::toResponse)
                .toList();
        return PageResponse.of(items, safePage, safeSize, endpointService.count(tenantId));
    }

    @GetMapping("/{endpointId}")
    @Operation(summary = "Fetch one endpoint")
    public EndpointResponse get(@PathVariable long endpointId) {
        return toResponse(endpointService.require(ApiKeyPrincipal.Context.requireTenantId(), endpointId));
    }

    @PostMapping("/{endpointId}/pause")
    @Operation(summary = "Pause an endpoint; queued and retrying deliveries are cancelled")
    public EndpointResponse pause(@PathVariable long endpointId) {
        requireScope(SCOPE);
        return toResponse(endpointService.pause(ApiKeyPrincipal.Context.requireTenantId(), endpointId));
    }

    @PostMapping("/{endpointId}/resume")
    @Operation(summary = "Resume an endpoint; cancelled deliveries are not resurrected")
    public EndpointResponse resume(@PathVariable long endpointId) {
        requireScope(SCOPE);
        return toResponse(endpointService.resume(ApiKeyPrincipal.Context.requireTenantId(), endpointId));
    }

    /**
     * Soft-deletes the endpoint. 404 if it is already deleted: the resource is gone as far
     * as this API is concerned, and answering 204 would suggest a retry could ever bring it
     * back.
     *
     * <p>Deliveries that were still queued or waiting for a retry are cancelled; the ones
     * already being delivered are left to finish and record their outcome.
     */
    @DeleteMapping("/{endpointId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an endpoint; queued deliveries are cancelled and the URL is released")
    public void delete(@PathVariable long endpointId) {
        requireScope(SCOPE);
        endpointService.delete(ApiKeyPrincipal.Context.requireTenantId(), endpointId);
    }

    private static EndpointResponse toResponse(Endpoint endpoint) {
        return new EndpointResponse(endpoint.id(), endpoint.url(), endpoint.subscribedTypes(),
                endpoint.maxAttempts(), endpoint.maxConcurrency(), endpoint.status(),
                endpoint.createdAt());
    }

    private void requireScope(String scope) {
        ApiKeyPrincipal principal = ApiKeyPrincipal.Context.get();
        if (principal != null && !principal.hasScope(scope)) {
            throw new ApiException(dev.hookflow.common.error.ErrorCode.FORBIDDEN,
                    "API key lacks scope " + scope);
        }
    }
}

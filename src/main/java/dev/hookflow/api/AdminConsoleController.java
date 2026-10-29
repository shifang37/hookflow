package dev.hookflow.api;

import dev.hookflow.api.dto.Dtos.ApiKeyResponse;
import dev.hookflow.api.dto.Dtos.LatencyResponse;
import dev.hookflow.api.dto.Dtos.OverviewResponse;
import dev.hookflow.api.dto.Dtos.TenantResponse;
import dev.hookflow.api.dto.Dtos.TenantStatsResponse;
import dev.hookflow.api.dto.Dtos.TimeseriesResponse;
import dev.hookflow.api.dto.Dtos.UpdateTenantRequest;
import dev.hookflow.common.auth.BootstrapTokenGuard;
import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.web.PageResponse;
import dev.hookflow.domain.tenant.Tenant;
import dev.hookflow.persistence.ApiKeyQueryMapper;
import dev.hookflow.service.ReportService;
import dev.hookflow.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read side of the operator console, plus the one write it performs (tenant policy).
 *
 * <p>Split from {@link AdminController} because the two answer different questions:
 * that one <i>provisions</i> (create tenant, mint and revoke keys) and this one
 * <i>observes</i> (who exists, how are they doing, what is the platform doing). Both
 * sit behind the same bootstrap token; {@link BootstrapTokenGuard} is the single
 * implementation of that check.
 *
 * <p>Platform-wide aggregates live here and not under a tenant-scoped route on
 * purpose: {@code /v1/**} is authenticated by a tenant API key, and a tenant must not
 * be able to read another tenant's volume through a "dashboard" endpoint.
 */
@RestController
@RequestMapping("/v1/admin")
@Profile({"api", "all"})
@Tag(name = "Admin console", description = "Operator views: tenants, statistics, platform overview")
public class AdminConsoleController {

    private final ReportService reportService;
    private final TenantService tenantService;
    private final BootstrapTokenGuard bootstrapTokenGuard;
    private final ApiKeyQueryMapper apiKeyQueryMapper;

    public AdminConsoleController(ReportService reportService,
                                  TenantService tenantService,
                                  BootstrapTokenGuard bootstrapTokenGuard,
                                  ApiKeyQueryMapper apiKeyQueryMapper) {
        this.reportService = reportService;
        this.tenantService = tenantService;
        this.bootstrapTokenGuard = bootstrapTokenGuard;
        this.apiKeyQueryMapper = apiKeyQueryMapper;
    }

    @GetMapping("/tenants")
    @Operation(summary = "List tenants with endpoint/key counts, newest first")
    public PageResponse<TenantResponse> listTenants(
            @RequestHeader("X-Bootstrap-Token") String bootstrapToken,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        bootstrapTokenGuard.verify(bootstrapToken);
        return reportService.listTenants(search, page, size);
    }

    @GetMapping("/tenants/{tenantId}")
    @Operation(summary = "Fetch one tenant with its endpoint and key counts")
    public TenantResponse getTenant(@RequestHeader("X-Bootstrap-Token") String bootstrapToken,
                                    @PathVariable long tenantId) {
        bootstrapTokenGuard.verify(bootstrapToken);
        return reportService.tenant(tenantId);
    }

    @GetMapping("/tenants/{tenantId}/stats")
    @Operation(summary = "Delivery statistics for a tenant over a fixed window (24h, 7d, 30d, 90d)")
    public TenantStatsResponse tenantStats(@RequestHeader("X-Bootstrap-Token") String bootstrapToken,
                                          @PathVariable long tenantId,
                                          @RequestParam(defaultValue = "7d") String range) {
        bootstrapTokenGuard.verify(bootstrapToken);
        return reportService.tenantStats(tenantId, range);
    }

    @GetMapping("/tenants/{tenantId}/keys")
    @Operation(summary = "List a tenant's API keys (metadata only; the secret is never stored in readable form)")
    public List<ApiKeyResponse> tenantKeys(@RequestHeader("X-Bootstrap-Token") String bootstrapToken,
                                           @PathVariable long tenantId) {
        bootstrapTokenGuard.verify(bootstrapToken);
        // Same 404 as the tenant detail endpoint: an empty list would be indistinguishable
        // from "this tenant is fine and has no keys", which is a different situation.
        tenantService.require(tenantId);
        return apiKeyQueryMapper.listByTenant(tenantId).stream()
                .map(row -> new ApiKeyResponse(row.id(), row.keyPrefix(), row.scopes(),
                        row.status(), row.createdAt(), row.revokedAt()))
                .toList();
    }

    @PatchMapping("/tenants/{tenantId}")
    @Operation(summary = "Update a tenant's rate limit, concurrency budget or fairness weight")
    public TenantResponse updateTenant(@RequestHeader("X-Bootstrap-Token") String bootstrapToken,
                                       @PathVariable long tenantId,
                                       @RequestBody(required = false) UpdateTenantRequest request) {
        bootstrapTokenGuard.verify(bootstrapToken);
        if (request == null) {
            throw ApiException.invalid("request body is required");
        }
        Tenant updated = tenantService.updateConfig(tenantId, request.rateLimit(),
                request.maxInFlight(), request.weight());
        // The counts are not part of the policy update; read them back once so the
        // console can replace its row without a second request.
        TenantResponse counts = reportService.tenant(tenantId);
        return new TenantResponse(updated.id(), updated.name(), updated.status(),
                updated.rateLimit(), updated.maxInFlight(), updated.weight(),
                counts.endpointCount(), counts.activeKeyCount(), updated.createdAt());
    }

    @GetMapping("/overview")
    @Operation(summary = "Platform-wide delivery statistics over a fixed window")
    public OverviewResponse overview(@RequestHeader("X-Bootstrap-Token") String bootstrapToken,
                                     @RequestParam(defaultValue = "24h") String range) {
        bootstrapTokenGuard.verify(bootstrapToken);
        return reportService.overview(range);
    }

    /**
     * Bucketed counters for the throughput chart.
     *
     * <p>{@code tenantId} is optional and lives in the operator domain: the tenant
     * detail page charts one customer, the overview charts the platform. Both are the
     * same endpoint because both are the same question at a different scope.
     */
    @GetMapping("/metrics/timeseries")
    @Operation(summary = "Bucketed event, attempt and settlement counts for a chart")
    public TimeseriesResponse timeseries(@RequestHeader("X-Bootstrap-Token") String bootstrapToken,
                                         @RequestParam(defaultValue = "24h") String range,
                                         @RequestParam(defaultValue = "auto") String interval,
                                         @RequestParam(required = false) Long tenantId) {
        bootstrapTokenGuard.verify(bootstrapToken);
        return reportService.timeseries(range, interval, tenantId);
    }

    /** Latency distribution of individual attempts, with nearest-rank percentiles. */
    @GetMapping("/metrics/latency")
    @Operation(summary = "Attempt duration histogram and p50/p95/p99")
    public LatencyResponse latency(@RequestHeader("X-Bootstrap-Token") String bootstrapToken,
                                   @RequestParam(defaultValue = "24h") String range,
                                   @RequestParam(required = false) Long tenantId) {
        bootstrapTokenGuard.verify(bootstrapToken);
        return reportService.latency(range, tenantId);
    }
}

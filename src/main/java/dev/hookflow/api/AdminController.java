package dev.hookflow.api;

import dev.hookflow.api.dto.Dtos.ProvisionTenantRequest;
import dev.hookflow.api.dto.Dtos.ProvisionTenantResponse;
import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.persistence.ApiKeyMapper;
import dev.hookflow.persistence.TenantMapper;
import dev.hookflow.service.ApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Operator-only provisioning.
 *
 * <p>Guarded by a bootstrap token from the environment, not by a tenant API key:
 * bootstrapping is a different trust boundary, and mixing the two would mean the
 * first tenant could mint more tenants.
 */
@RestController
@RequestMapping("/v1/admin")
@Profile({"api", "all"})
@Tag(name = "Admin", description = "Operator provisioning")
public class AdminController {

    private static final String DEFAULT_SCOPES = "event:write,endpoint:write,delivery:read";

    private final TenantMapper tenantMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final ApiKeyService apiKeyService;
    private final HookFlowProperties properties;

    public AdminController(TenantMapper tenantMapper,
                           ApiKeyMapper apiKeyMapper,
                           ApiKeyService apiKeyService,
                           HookFlowProperties properties) {
        this.tenantMapper = tenantMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.apiKeyService = apiKeyService;
        this.properties = properties;
    }

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a tenant and issue its first API key")
    @Transactional
    public ProvisionTenantResponse provisionTenant(
            @RequestHeader("X-Bootstrap-Token") String bootstrapToken,
            @RequestBody ProvisionTenantRequest request) {
        verify(bootstrapToken);
        if (request.name() == null || request.name().isBlank() || request.name().length() > 64) {
            throw ApiException.invalid("name is required and must be at most 64 characters");
        }
        int rateLimit = request.rateLimit() == null ? 100 : request.rateLimit();
        if (rateLimit < 0) {
            throw ApiException.invalid("rateLimit must be >= 0");
        }
        tenantMapper.insert(request.name(), rateLimit);
        var tenant = tenantMapper.findByName(request.name())
                .orElseThrow(() -> new IllegalStateException("tenant insert did not persist"));

        if (request.maxInFlight() != null || request.weight() != null) {
            int maxInFlight = request.maxInFlight() == null ? tenant.maxInFlight() : request.maxInFlight();
            int weight = request.weight() == null ? tenant.weight() : request.weight();
            if (maxInFlight < 0) {
                throw ApiException.invalid("maxInFlight must be >= 0 (0 = uncapped)");
            }
            if (weight < 1 || weight > 100) {
                throw ApiException.invalid("weight must be between 1 and 100");
            }
            tenantMapper.updateBudget(tenant.id(), maxInFlight, weight);
        }

        ApiKeyService.IssuedKey key = apiKeyService.issue(tenant.id(), DEFAULT_SCOPES);
        return new ProvisionTenantResponse(tenant.id(), tenant.name(), key.token(), key.keyPrefix());
    }

    @PostMapping("/tenants/{tenantId}/keys")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Issue an additional API key for an existing tenant")
    public ProvisionTenantResponse issueKey(@RequestHeader("X-Bootstrap-Token") String bootstrapToken,
                                            @PathVariable long tenantId) {
        verify(bootstrapToken);
        var tenant = tenantMapper.findById(tenantId)
                .orElseThrow(() -> ApiException.notFound("Tenant"));
        ApiKeyService.IssuedKey key = apiKeyService.issue(tenantId, DEFAULT_SCOPES);
        return new ProvisionTenantResponse(tenant.id(), tenant.name(), key.token(), key.keyPrefix());
    }

    @PostMapping("/keys/{apiKeyId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke an API key")
    public void revoke(@RequestHeader("X-Bootstrap-Token") String bootstrapToken,
                       @PathVariable long apiKeyId) {
        verify(bootstrapToken);
        apiKeyService.revoke(apiKeyId);
    }

    private void verify(String provided) {
        String expected = properties.admin() == null ? null : properties.admin().bootstrapToken();
        if (expected == null || expected.isBlank()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Admin API is not configured");
        }
        boolean match = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                (provided == null ? "" : provided).getBytes(StandardCharsets.UTF_8));
        if (!match) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Invalid bootstrap token");
        }
    }
}

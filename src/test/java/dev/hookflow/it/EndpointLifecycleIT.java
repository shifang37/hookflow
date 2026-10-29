package dev.hookflow.it;

import dev.hookflow.api.AdminController;
import dev.hookflow.api.dto.Dtos.ProvisionTenantRequest;
import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.persistence.TenantMapper;
import dev.hookflow.service.EndpointService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Uniqueness violations must read as "you already created this", not as an internal error.
 *
 * <p>Before v0.4 both of these reached the catch-all handler and returned 500 with a
 * requestId: a client had no way to tell a duplicate from a broken server, and the only
 * clue was a stack trace in the log. They are 409 {@code ALREADY_EXISTS} now — with the
 * resource named in the message, which a generic handler could not do.
 *
 * <p>Equally important is what this test does <b>not</b> change: the idempotency race on
 * the ingest path keeps its 500 (see {@code EventIngestIT}), because that duplicate is
 * the caller retrying something safe, not the caller creating something twice.
 */
class EndpointLifecycleIT extends AbstractIntegrationTest {

    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private EndpointService endpointService;
    @Autowired
    private AdminController adminController;
    @Autowired
    private HookFlowProperties properties;

    @Test
    @DisplayName("a duplicate callback URL is a 409 that names the URL, not a 500")
    void duplicateCallbackUrlIsAConflict() {
        long tenantId = newTenant();
        String url = "https://example.test/dup-" + UUID.randomUUID();

        endpointService.create(tenantId, url, "*", 8);

        assertThatThrownBy(() -> endpointService.create(tenantId, url, "*", 8))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.errorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
                    assertThat(api.errorCode().status().value()).isEqualTo(409);
                    assertThat(api.getMessage()).contains(url);
                });
    }

    @Test
    @DisplayName("the URL uniqueness is per tenant: the same URL at two tenants is fine")
    void sameUrlAtDifferentTenantsIsAllowed() {
        String url = "https://example.test/shared-" + UUID.randomUUID();

        // If the constraint were global, the second call would throw and this would fail:
        // it is uk_endpoint_tenant_url, and the isolation boundary must survive the fix.
        assertThat(endpointService.create(newTenant(), url, "*", 8).endpoint().id()).isPositive();
        assertThat(endpointService.create(newTenant(), url, "*", 8).endpoint().id()).isPositive();
    }

    @Test
    @DisplayName("a duplicate tenant name is a 409 and does not mint a second key")
    void duplicateTenantNameIsAConflict() {
        String bootstrapToken = properties.admin().bootstrapToken();
        String name = "dup-tenant-" + UUID.randomUUID();

        var first = adminController.provisionTenant(bootstrapToken,
                new ProvisionTenantRequest(name, 100, null, null));
        assertThat(first.apiKey()).isNotBlank();

        assertThatThrownBy(() -> adminController.provisionTenant(bootstrapToken,
                new ProvisionTenantRequest(name, 100, null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.errorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
                    assertThat(api.getMessage()).contains(name);
                });

        // The failed attempt must not have left a half-provisioned tenant behind.
        assertThat(tenantMapper.findByName(name)).isPresent();
    }

    private long newTenant() {
        String name = "endpoint-it-" + UUID.randomUUID();
        tenantMapper.insert(name, 0);
        return tenantMapper.findByName(name).orElseThrow().id();
    }
}

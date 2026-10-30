package dev.hookflow.it;

import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.domain.tenant.Tenant;
import dev.hookflow.persistence.TenantMapper;
import dev.hookflow.service.TenantService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Suspension is a real state transition, not just a field in a response: the status is
 * persisted, {@code requireActive} refuses the tenant, and the scheduling snapshot stops
 * offering it. Reactivation has to undo all three.
 */
class TenantSuspendIT extends AbstractIntegrationTest {

    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private TenantService tenantService;

    @Test
    @DisplayName("suspending blocks active-only reads and removes the tenant from the scheduler")
    void suspendAndReactivate() {
        String name = "suspend-it-" + UUID.randomUUID();
        tenantMapper.insert(name, 0);
        Tenant tenant = tenantMapper.findByName(name).orElseThrow();

        Tenant suspended = tenantService.updateConfig(tenant.id(), null, null, null, "SUSPENDED");

        assertThat(suspended.status()).isEqualTo(Tenant.SUSPENDED);
        assertThat(tenantMapper.findById(tenant.id()).orElseThrow().status())
                .isEqualTo(Tenant.SUSPENDED);
        assertThat(tenantMapper.listBudgets())
                .as("a suspended tenant must not occupy a weighted-round-robin slot")
                .noneMatch(budget -> budget.id().equals(tenant.id()));
        assertThatThrownBy(() -> tenantService.requireActive(tenant.id()))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).errorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));

        Tenant active = tenantService.updateConfig(tenant.id(), null, null, null, "ACTIVE");

        assertThat(active.status()).isEqualTo(Tenant.ACTIVE);
        assertThat(tenantService.requireActive(tenant.id()).status()).isEqualTo(Tenant.ACTIVE);
        assertThat(tenantMapper.listBudgets())
                .anyMatch(budget -> budget.id().equals(tenant.id()));
    }
}

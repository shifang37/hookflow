package dev.hookflow.service;

import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.domain.tenant.Tenant;
import dev.hookflow.fairness.AdmissionControl;
import dev.hookflow.persistence.TenantMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The tenant lifecycle is a policy update, not a delete: suspending flips one column and
 * lets {@link TenantService#requireActive} do the enforcement. These tests pin both halves
 * — the merged row written to the mapper, and the refusal that follows.
 */
class TenantServiceTest {

    private final TenantMapper tenantMapper = mock(TenantMapper.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AdmissionControl> admissionControl = mock(ObjectProvider.class);
    private final TenantService service = new TenantService(tenantMapper, admissionControl);

    @Test
    @DisplayName("suspending only changes status, leaving the policy fields untouched")
    void suspendsWithoutTouchingPolicy() {
        when(tenantMapper.findById(1L))
                .thenReturn(Optional.of(tenant("ACTIVE")), Optional.of(tenant("SUSPENDED")));

        Tenant updated = service.updateConfig(1L, null, null, null, "SUSPENDED");

        assertThat(updated.status()).isEqualTo("SUSPENDED");
        verify(tenantMapper).updateConfig(1L, 100, 32, 2, "SUSPENDED");
    }

    @Test
    @DisplayName("a status is normalised, so a lowercase or padded value is accepted")
    void normalisesStatus() {
        when(tenantMapper.findById(1L))
                .thenReturn(Optional.of(tenant("ACTIVE")), Optional.of(tenant("SUSPENDED")));

        service.updateConfig(1L, null, null, null, "  suspended  ");

        verify(tenantMapper).updateConfig(1L, 100, 32, 2, "SUSPENDED");
    }

    @Test
    @DisplayName("reactivating a suspended tenant is allowed and restores ACTIVE")
    void reactivates() {
        when(tenantMapper.findById(1L))
                .thenReturn(Optional.of(tenant("SUSPENDED")), Optional.of(tenant("ACTIVE")));

        Tenant updated = service.updateConfig(1L, null, null, null, "ACTIVE");

        assertThat(updated.status()).isEqualTo("ACTIVE");
        verify(tenantMapper).updateConfig(1L, 100, 32, 2, "ACTIVE");
    }

    @Test
    @DisplayName("an unknown status is a 400 and writes nothing")
    void rejectsUnknownStatus() {
        when(tenantMapper.findById(1L)).thenReturn(Optional.of(tenant("ACTIVE")));

        assertThatThrownBy(() -> service.updateConfig(1L, null, null, null, "DELETED"))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).errorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));

        verify(tenantMapper, never())
                .updateConfig(anyLong(), anyInt(), anyInt(), anyInt(), anyString());
    }

    @Test
    @DisplayName("an update with no fields at all is rejected before the read")
    void rejectsEmptyUpdate() {
        assertThatThrownBy(() -> service.updateConfig(1L, null, null, null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).errorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));

        verify(tenantMapper, never()).findById(1L);
    }

    private static Tenant tenant(String status) {
        return new Tenant(1L, "acme", 100, 32, 2, status,
                LocalDateTime.of(2026, 10, 20, 3, 11, 8));
    }
}

package dev.hookflow.service;

import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.domain.tenant.Tenant;
import dev.hookflow.persistence.TenantMapper;
import org.springframework.stereotype.Service;

@Service
public class TenantService {

    private final TenantMapper tenantMapper;

    public TenantService(TenantMapper tenantMapper) {
        this.tenantMapper = tenantMapper;
    }

    /**
     * @throws ApiException when the tenant is missing or suspended
     */
    public Tenant requireActive(long tenantId) {
        Tenant tenant = tenantMapper.findById(tenantId)
                .orElseThrow(() -> ApiException.notFound("Tenant"));
        if (!tenant.isActive()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Tenant is suspended");
        }
        return tenant;
    }

    public Tenant require(long tenantId) {
        return tenantMapper.findById(tenantId).orElseThrow(() -> ApiException.notFound("Tenant"));
    }
}

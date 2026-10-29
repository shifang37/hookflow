package dev.hookflow.service;

import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.domain.tenant.Tenant;
import dev.hookflow.fairness.AdmissionControl;
import dev.hookflow.persistence.TenantMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantService {

    private final TenantMapper tenantMapper;
    /**
     * Optional: {@code AdmissionControl} only exists in the {@code relay}/{@code worker}/
     * {@code all} profiles. In a pure {@code api} deployment nothing schedules, so there
     * is no snapshot to invalidate — but the bean must not be required, or the api
     * profile would fail to start.
     */
    private final ObjectProvider<AdmissionControl> admissionControl;

    public TenantService(TenantMapper tenantMapper, ObjectProvider<AdmissionControl> admissionControl) {
        this.tenantMapper = tenantMapper;
        this.admissionControl = admissionControl;
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

    /**
     * Applies a partial policy update: {@code null} fields keep their current value.
     *
     * <p>The scheduler reads budgets from a snapshot, so a rate/weight change would
     * otherwise take up to one refresh interval to be honoured. Refreshing here makes
     * an operator's change visible on the next publish, which is the difference between
     * "I fixed the fairness weights" and "maybe I fixed them".
     *
     * @throws ApiException {@code INVALID_REQUEST} when nothing is provided or a value
     *                      is out of range
     */
    @Transactional
    public Tenant updateConfig(long tenantId, Integer rateLimit, Integer maxInFlight, Integer weight) {
        if (rateLimit == null && maxInFlight == null && weight == null) {
            throw ApiException.invalid("at least one of rateLimit, maxInFlight, weight is required");
        }
        Tenant current = require(tenantId);

        int newRateLimit = rateLimit == null ? current.rateLimit() : rateLimit;
        int newMaxInFlight = maxInFlight == null ? current.maxInFlight() : maxInFlight;
        int newWeight = weight == null ? current.weight() : weight;

        if (newRateLimit < 0) {
            throw ApiException.invalid("rateLimit must be >= 0 (0 = unlimited)");
        }
        if (newMaxInFlight < 0) {
            throw ApiException.invalid("maxInFlight must be >= 0 (0 = uncapped)");
        }
        if (newWeight < 1 || newWeight > 100) {
            throw ApiException.invalid("weight must be between 1 and 100");
        }

        tenantMapper.updateConfig(tenantId, newRateLimit, newMaxInFlight, newWeight);
        admissionControl.ifAvailable(AdmissionControl::refresh);
        return require(tenantId);
    }
}

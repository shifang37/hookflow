package dev.hookflow.service;

import dev.hookflow.common.error.ApiException;
import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.crypto.SecretCipher;
import dev.hookflow.domain.endpoint.Endpoint;
import dev.hookflow.persistence.AuditLogMapper;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.persistence.EndpointMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * Endpoint lifecycle. Creating an endpoint mints a signing secret, stores it
 * encrypted, and returns the plaintext exactly once.
 */
@Service
public class EndpointService {

    private static final int SECRET_BYTES = 32;

    private final EndpointMapper endpointMapper;
    private final DeliveryMapper deliveryMapper;
    private final AuditLogMapper auditLogMapper;
    private final SecretCipher secretCipher;
    private final HookFlowProperties properties;
    private final EgressGuard egressGuard;
    private final SecureRandom random = new SecureRandom();

    public EndpointService(EndpointMapper endpointMapper,
                           DeliveryMapper deliveryMapper,
                           AuditLogMapper auditLogMapper,
                           SecretCipher secretCipher,
                           HookFlowProperties properties,
                           EgressGuard egressGuard) {
        this.endpointMapper = endpointMapper;
        this.deliveryMapper = deliveryMapper;
        this.auditLogMapper = auditLogMapper;
        this.secretCipher = secretCipher;
        this.properties = properties;
        this.egressGuard = egressGuard;
    }

    public record CreatedEndpoint(Endpoint endpoint, String signingSecret) {
    }

    @Transactional
    public CreatedEndpoint create(long tenantId, String url, String subscribedTypes, Integer maxAttempts) {
        egressGuard.validate(url);
        String types = (subscribedTypes == null || subscribedTypes.isBlank()) ? Endpoint.WILDCARD : subscribedTypes;
        int attempts = maxAttempts == null ? properties.retry().defaultMaxAttempts() : maxAttempts;
        if (attempts < 1 || attempts > 50) {
            throw ApiException.invalid("maxAttempts must be between 1 and 50");
        }

        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        byte[] cipher = secretCipher.encrypt(secret);

        endpointMapper.insert(tenantId, url, cipher, secretCipher.keyVersion(), types, attempts);
        Endpoint endpoint = endpointMapper.findByTenantAndUrl(tenantId, url)
                .orElseThrow(() -> new IllegalStateException("endpoint insert did not persist"));
        auditLogMapper.insert(tenantId, "api-key", "endpoint.create", "endpoint", endpoint.id(),
                "{\"url\":\"" + endpoint.url() + "\"}");
        return new CreatedEndpoint(endpoint, Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
    }

    public List<Endpoint> list(long tenantId, int page, int size) {
        return endpointMapper.listByTenant(tenantId, size, page * size);
    }

    public long count(long tenantId) {
        return endpointMapper.countByTenant(tenantId);
    }

    public Endpoint require(long tenantId, long endpointId) {
        return endpointMapper.findByIdAndTenant(endpointId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Endpoint"));
    }

    /**
     * Pausing stops new deliveries and cancels in-flight retries + queued
     * deliveries. Already-delivering attempts are left to finish and record their
     * outcome; resume does not resurrect cancelled deliveries.
     */
    @Transactional
    public Endpoint pause(long tenantId, long endpointId) {
        Endpoint endpoint = require(tenantId, endpointId);
        if (Endpoint.PAUSED.equals(endpoint.status())) {
            return endpoint;
        }
        endpointMapper.updateStatus(endpointId, tenantId, Endpoint.PAUSED);
        int cancelled = deliveryMapper.cancelPendingForEndpoint(endpointId, tenantId);
        auditLogMapper.insert(tenantId, "api-key", "endpoint.pause", "endpoint", endpointId,
                "{\"cancelledDeliveries\":" + cancelled + "}");
        return require(tenantId, endpointId);
    }

    @Transactional
    public Endpoint resume(long tenantId, long endpointId) {
        require(tenantId, endpointId);
        endpointMapper.updateStatus(endpointId, tenantId, Endpoint.ACTIVE);
        auditLogMapper.insert(tenantId, "api-key", "endpoint.resume", "endpoint", endpointId, "{}");
        return require(tenantId, endpointId);
    }
}

package dev.hookflow.service;

import dev.hookflow.common.RetryOnLockConflict;
import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.crypto.SecretCipher;
import dev.hookflow.domain.endpoint.Endpoint;
import dev.hookflow.persistence.AuditLogMapper;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.persistence.EndpointMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transactionTemplate;
    private final RetryOnLockConflict retryOnLockConflict;
    private final SecureRandom random = new SecureRandom();

    public EndpointService(EndpointMapper endpointMapper,
                           DeliveryMapper deliveryMapper,
                           AuditLogMapper auditLogMapper,
                           SecretCipher secretCipher,
                           HookFlowProperties properties,
                           EgressGuard egressGuard,
                           TransactionTemplate transactionTemplate,
                           RetryOnLockConflict retryOnLockConflict) {
        this.endpointMapper = endpointMapper;
        this.deliveryMapper = deliveryMapper;
        this.auditLogMapper = auditLogMapper;
        this.secretCipher = secretCipher;
        this.properties = properties;
        this.egressGuard = egressGuard;
        this.transactionTemplate = transactionTemplate;
        this.retryOnLockConflict = retryOnLockConflict;
    }

    public record CreatedEndpoint(Endpoint endpoint, String signingSecret) {
    }

    @Transactional
    public CreatedEndpoint create(long tenantId, String url, String subscribedTypes, Integer maxAttempts) {
        return create(tenantId, url, subscribedTypes, maxAttempts, null);
    }

    /**
     * @param maxConcurrency parallel POSTs allowed to this endpoint; null / 0 means
     *                       "the tenant's budget is the only fence". A receiver that
     *                       falls over above N concurrent calls declares N here, and
     *                       the scheduler stops at N instead of letting that receiver
     *                       slow down the whole tenant.
     */
    @Transactional
    public CreatedEndpoint create(long tenantId, String url, String subscribedTypes,
                                  Integer maxAttempts, Integer maxConcurrency) {
        egressGuard.validate(url);
        String types = (subscribedTypes == null || subscribedTypes.isBlank()) ? Endpoint.WILDCARD : subscribedTypes;
        int attempts = maxAttempts == null ? properties.retry().defaultMaxAttempts() : maxAttempts;
        if (attempts < 1 || attempts > 50) {
            throw ApiException.invalid("maxAttempts must be between 1 and 50");
        }
        int concurrency = maxConcurrency == null ? 0 : maxConcurrency;
        if (concurrency < 0 || concurrency > 1000) {
            throw ApiException.invalid("maxConcurrency must be between 0 (uncapped) and 1000");
        }

        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        byte[] cipher = secretCipher.encrypt(secret);

        try {
            endpointMapper.insert(tenantId, url, cipher, secretCipher.keyVersion(), types, attempts,
                    concurrency);
        } catch (DuplicateKeyException alreadyExists) {
            // uk_endpoint_tenant_url. Translated here rather than in a global handler on
            // purpose: the ingest path lets its own DuplicateKeyException reach the 500
            // handler (a lost idempotency race is not a conflict the caller can act on),
            // so a blanket mapping would silently change that documented behaviour.
            throw new ApiException(ErrorCode.ALREADY_EXISTS,
                    "an endpoint with this url already exists: " + url);
        }
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
     *
     * <p><b>Deadlock is expected here and retried</b> by the shared
     * {@link RetryOnLockConflict} component (metric
     * {@code hookflow.db.lock_conflict{operation="endpoint.pause"}}). The cancel is
     * one bulk {@code UPDATE ... WHERE endpoint_id = ? AND status IN (...)} while the
     * workers are claiming and settling individual rows in primary-key order. InnoDB
     * can pick that bulk statement as the deadlock victim, and that is not a bug to
     * be designed away — it is what a long-running status change against a hot table
     * looks like. Retrying the whole transaction is the standard answer; the
     * operation is idempotent, so a replay is harmless. Found by {@code FairnessIT};
     * see {@code docs/failures/0007-cancel-vs-claim-deadlock.md}.
     */
    public Endpoint pause(long tenantId, long endpointId) {
        return retryOnLockConflict.execute("endpoint.pause",
                () -> transactionTemplate.execute(status -> pauseInTransaction(tenantId, endpointId)));
    }

    private Endpoint pauseInTransaction(long tenantId, long endpointId) {
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

    /**
     * Soft-deletes the endpoint: its queued and retrying deliveries are cancelled, its URL
     * is released for re-creation, and every id-scoped read starts answering 404.
     *
     * <p>Delivery history is deliberately kept — {@code delivery.endpoint_id} is a foreign
     * key, and "which callback address received this event" must remain answerable after
     * the endpoint is gone. {@code V5__endpoint_soft_delete.sql} explains how the URL
     * unique key survives that.
     *
     * <p><b>Deadlock is expected here for the same reason as in {@link #pause}</b>: the
     * cancel is one bulk update against the delivery table while workers claim and settle
     * rows. The whole transaction is retried by {@link RetryOnLockConflict}, tagged
     * {@code endpoint.delete}; unlike pause there is no operation that undoes it, so the
     * retry is the only automatic recovery.
     */
    public void delete(long tenantId, long endpointId) {
        retryOnLockConflict.executeWithoutResult("endpoint.delete",
                () -> transactionTemplate.executeWithoutResult(
                        status -> deleteInTransaction(tenantId, endpointId)));
    }

    private void deleteInTransaction(long tenantId, long endpointId) {
        Endpoint endpoint = require(tenantId, endpointId);
        // The CAS, not the read above, decides the race: two operators deleting the same
        // endpoint must not both get a 204 (and two audit rows) out of one endpoint.
        if (endpointMapper.markDeleted(endpointId, tenantId) == 0) {
            throw ApiException.notFound("Endpoint");
        }
        // Order matters no more than in pause: both statements are in one transaction, and
        // an ingest that races them is handled by the worker, which cancels a delivery
        // whose endpoint is already inactive.
        int cancelled = deliveryMapper.cancelPendingForEndpoint(endpointId, tenantId);
        auditLogMapper.insert(tenantId, "api-key", "endpoint.delete", "endpoint", endpointId,
                "{\"url\":\"" + endpoint.url() + "\",\"cancelledDeliveries\":" + cancelled + "}");
    }
}

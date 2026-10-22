package dev.hookflow.domain.apikey;

import java.time.LocalDateTime;

/**
 * API key metadata. The plaintext secret exists only in the creation response;
 * what is persisted is SHA-256(secret) plus an 8 character lookup prefix.
 */
public record ApiKey(
        Long id,
        Long tenantId,
        String keyPrefix,
        String keyHash,
        String scopes,
        String status,
        LocalDateTime createdAt,
        LocalDateTime revokedAt) {

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public boolean hasScope(String scope) {
        if (scopes == null || scopes.isBlank()) {
            return false;
        }
        for (String candidate : scopes.split(",")) {
            if (candidate.trim().equals(scope)) {
                return true;
            }
        }
        return false;
    }
}

package dev.hookflow.domain.audit;

/** Append-only record of state-changing operations that a tenant may later ask about. */
public record AuditLog(
        Long id,
        Long tenantId,
        String actor,
        String action,
        String targetType,
        Long targetId,
        String detail,
        java.time.LocalDateTime createdAt) {
}

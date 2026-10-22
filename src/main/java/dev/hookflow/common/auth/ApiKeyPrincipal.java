package dev.hookflow.common.auth;

/**
 * The authenticated caller for the current request.
 *
 * <p>Held in a {@link ThreadLocal} that the auth filter always clears in a
 * finally block. Request-scoped beans would work too, but an explicit holder
 * keeps the service layer free of web types.
 */
public record ApiKeyPrincipal(long tenantId, String keyPrefix, String scopes) {

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

    public static final class Context {
        private static final ThreadLocal<ApiKeyPrincipal> HOLDER = new ThreadLocal<>();

        private Context() {
        }

        public static void set(ApiKeyPrincipal principal) {
            HOLDER.set(principal);
        }

        public static ApiKeyPrincipal get() {
            return HOLDER.get();
        }

        public static long requireTenantId() {
            ApiKeyPrincipal principal = HOLDER.get();
            if (principal == null) {
                throw new IllegalStateException("No authenticated principal in this thread");
            }
            return principal.tenantId();
        }

        public static void clear() {
            HOLDER.remove();
        }
    }
}

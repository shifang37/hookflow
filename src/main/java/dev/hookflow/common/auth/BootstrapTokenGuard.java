package dev.hookflow.common.auth;

import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.config.HookFlowProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The operator trust domain: a shared bootstrap token from the environment.
 *
 * <p>Separate from {@link ApiKeyPrincipal} on purpose. An API key authenticates a
 * <i>tenant</i> and is scoped to that tenant's own rows; the bootstrap token
 * authenticates an <i>operator</i> and may read across tenants. Mixing the two would
 * let the first tenant mint more tenants, and would make every admin read endpoint
 * reachable with a customer credential.
 *
 * <p>Extracted from {@code AdminController} when the read-side admin endpoints were
 * added: two controllers now guard the same trust boundary, and two copies of a
 * constant-time comparison is one copy too many.
 */
@Component
public class BootstrapTokenGuard {

    private final HookFlowProperties properties;

    public BootstrapTokenGuard(HookFlowProperties properties) {
        this.properties = properties;
    }

    /**
     * @throws ApiException {@code FORBIDDEN} when the admin API is unconfigured or the
     *                      presented token does not match
     */
    public void verify(String provided) {
        String expected = properties.admin() == null ? null : properties.admin().bootstrapToken();
        if (expected == null || expected.isBlank()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Admin API is not configured");
        }
        // Constant-time: a byte-by-byte early return leaks the matching prefix of the
        // token to anyone who can time the response.
        boolean match = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                (provided == null ? "" : provided).getBytes(StandardCharsets.UTF_8));
        if (!match) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Invalid bootstrap token");
        }
    }
}

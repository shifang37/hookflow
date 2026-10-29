package dev.hookflow.it;

import dev.hookflow.api.AdminConsoleController;
import dev.hookflow.api.dto.Dtos.ApiKeyResponse;
import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.persistence.TenantMapper;
import dev.hookflow.service.ApiKeyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The console's API-key view: rotate a key, see what exists, revoke the old one.
 *
 * <p>The important assertion is not that a list comes back — it is that the list is
 * enough to rotate safely. An operator needs to see the prefix (to tell keys apart), the
 * status (so a revoked key does not look live), and the revoke time (to know which one
 * was retired). None of that requires the secret, which by design cannot be read back.
 */
class ApiKeyConsoleIT extends AbstractIntegrationTest {

    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private AdminConsoleController console;
    @Autowired
    private HookFlowProperties properties;

    @Test
    @DisplayName("keys are listed with metadata, newest first, and no secret")
    void keysAreListedWithMetadata() {
        long tenantId = newTenant();
        ApiKeyService.IssuedKey first = apiKeyService.issue(tenantId, "event:write");
        ApiKeyService.IssuedKey second = apiKeyService.issue(tenantId, "event:write,delivery:read");

        List<ApiKeyResponse> keys = console.tenantKeys(bootstrapToken(), tenantId);

        assertThat(keys).hasSize(2);
        assertThat(keys.get(0).keyPrefix()).isEqualTo(second.keyPrefix());
        assertThat(keys.get(1).keyPrefix()).isEqualTo(first.keyPrefix());
        assertThat(keys).allSatisfy(key -> {
            assertThat(key.status()).isEqualTo("ACTIVE");
            assertThat(key.revokedAt()).isNull();
            assertThat(key.createdAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("revoking a key flips it to REVOKED and it stops authenticating")
    void revokeTakesEffect() {
        long tenantId = newTenant();
        ApiKeyService.IssuedKey issued = apiKeyService.issue(tenantId, "event:write");

        // It works before the revoke; the whole point is that it stops afterwards.
        assertThat(apiKeyService.authenticate(issued.token()).tenantId()).isEqualTo(tenantId);

        ApiKeyResponse listed = console.tenantKeys(bootstrapToken(), tenantId).getFirst();
        apiKeyService.revoke(listed.id());

        ApiKeyResponse revoked = console.tenantKeys(bootstrapToken(), tenantId).getFirst();
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(revoked.revokedAt()).isNotNull();

        assertThatThrownBy(() -> apiKeyService.authenticate(issued.token()))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).errorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    @DisplayName("revoked keys stay in the list, so a rotation is auditable afterwards")
    void revokedKeysRemainVisible() {
        long tenantId = newTenant();
        ApiKeyService.IssuedKey old = apiKeyService.issue(tenantId, "event:write");
        long oldId = console.tenantKeys(bootstrapToken(), tenantId).getFirst().id();
        apiKeyService.revoke(oldId);
        ApiKeyService.IssuedKey replacement = apiKeyService.issue(tenantId, "event:write");

        List<ApiKeyResponse> keys = console.tenantKeys(bootstrapToken(), tenantId);

        assertThat(keys).extracting(ApiKeyResponse::keyPrefix)
                .containsExactly(replacement.keyPrefix(), old.keyPrefix());
        assertThat(keys).extracting(ApiKeyResponse::status)
                .containsExactly("ACTIVE", "REVOKED");
    }

    @Test
    @DisplayName("an unknown tenant is a 404, not an empty list")
    void unknownTenantIsNotFound() {
        // "no such tenant" and "tenant with no keys" must not look the same on screen.
        assertThatThrownBy(() -> console.tenantKeys(bootstrapToken(), 999_999_999L))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).errorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    private long newTenant() {
        String name = "keys-it-" + UUID.randomUUID();
        tenantMapper.insert(name, 0);
        return tenantMapper.findByName(name).orElseThrow().id();
    }

    private String bootstrapToken() {
        return properties.admin().bootstrapToken();
    }
}

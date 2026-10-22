package dev.hookflow.service;

import dev.hookflow.common.auth.ApiKeyPrincipal;
import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.domain.apikey.ApiKey;
import dev.hookflow.persistence.ApiKeyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Issues and verifies API keys.
 *
 * <p>Token shape: {@code hk_<8-char prefix>_<32-char secret>}. Only the prefix and
 * SHA-256(secret) are stored, so a database dump does not hand out credentials.
 * Verification is a single indexed lookup plus a constant-time comparison.
 */
@Service
public class ApiKeyService {

    private static final String TOKEN_PREFIX = "hk_";
    private static final int PREFIX_LENGTH = 8;
    private static final int SECRET_LENGTH = 32;
    private static final char[] ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    private final ApiKeyMapper apiKeyMapper;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(ApiKeyMapper apiKeyMapper) {
        this.apiKeyMapper = apiKeyMapper;
    }

    /** The plaintext token is returned exactly once and never persisted. */
    public record IssuedKey(long apiKeyId, String token, String keyPrefix) {
    }

    @Transactional
    public IssuedKey issue(long tenantId, String scopes) {
        String prefix = randomString(PREFIX_LENGTH);
        String secret = randomString(SECRET_LENGTH);
        apiKeyMapper.insert(tenantId, prefix, sha256Hex(secret), scopes);
        ApiKey stored = apiKeyMapper.findByPrefix(prefix)
                .orElseThrow(() -> new IllegalStateException("api key insert did not persist"));
        return new IssuedKey(stored.id(), TOKEN_PREFIX + prefix + "_" + secret, prefix);
    }

    /**
     * @throws ApiException {@link ErrorCode#UNAUTHORIZED} when the token is malformed,
     *                      unknown, revoked, or belongs to a suspended tenant
     */
    public ApiKeyPrincipal authenticate(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Malformed API key");
        }
        String body = token.substring(TOKEN_PREFIX.length());
        int separator = body.indexOf('_');
        if (separator != PREFIX_LENGTH || body.length() <= PREFIX_LENGTH + 1) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Malformed API key");
        }
        String prefix = body.substring(0, PREFIX_LENGTH);
        String secret = body.substring(PREFIX_LENGTH + 1);

        Optional<ApiKey> found = apiKeyMapper.findByPrefix(prefix);
        if (found.isEmpty()) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Unknown API key");
        }
        ApiKey key = found.get();
        if (!key.isActive()) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "API key revoked");
        }
        byte[] expected = key.keyHash().getBytes(StandardCharsets.UTF_8);
        byte[] actual = sha256Hex(secret).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Invalid API key");
        }
        return new ApiKeyPrincipal(key.tenantId(), key.keyPrefix(), key.scopes());
    }

    @Transactional
    public void revoke(long apiKeyId) {
        if (apiKeyMapper.revoke(apiKeyId) == 0) {
            throw ApiException.notFound("API key");
        }
    }

    private String randomString(int length) {
        char[] buffer = new char[length];
        for (int i = 0; i < length; i++) {
            buffer[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(buffer);
    }

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

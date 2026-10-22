package dev.hookflow.api;

import dev.hookflow.common.auth.ApiKeyPrincipal;
import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.common.web.ApiErrorWriter;
import dev.hookflow.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Bearer API-key authentication for {@code /v1/**}.
 *
 * <p>Hand-rolled instead of Spring Security on purpose: v0.1 has exactly one
 * credential type and no roles beyond scopes, so a small filter is easier to
 * reason about than a filter chain. OAuth2 client credentials (v0.4) is the point
 * where Spring Security earns its keep — see ADR-0007.
 *
 * <p>Filter exceptions bypass {@code @RestControllerAdvice}, so failures are
 * written through {@link ApiErrorWriter} to keep the error shape identical.
 */
@Component
@Profile({"api", "all"})
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final ApiKeyService apiKeyService;
    private final ApiErrorWriter errorWriter;

    public ApiKeyAuthFilter(ApiKeyService apiKeyService, ApiErrorWriter errorWriter) {
        this.apiKeyService = apiKeyService;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Admin bootstrap is guarded by an operator token, not by tenant keys.
        if (path.startsWith("/v1/admin/")) {
            return true;
        }
        // The demo console's API has no credential: a tenant key in browser JS would
        // look like a security boundary while being readable in dev tools. The real
        // boundary is that the controller bean does not exist unless
        // hookflow.demo.enabled=true (local `all` profile only) - see docs/demo.md.
        if (path.startsWith("/v1/demo/")) {
            return true;
        }
        return !path.startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            errorWriter.write(request, response, ErrorCode.UNAUTHORIZED,
                    "Missing Authorization: Bearer <api-key>");
            return;
        }
        ApiKeyPrincipal principal;
        try {
            principal = apiKeyService.authenticate(header.substring(BEARER.length()).trim());
        } catch (ApiException e) {
            errorWriter.write(request, response, e.errorCode(), e.getMessage());
            return;
        }
        ApiKeyPrincipal.Context.set(principal);
        try {
            chain.doFilter(request, response);
        } finally {
            ApiKeyPrincipal.Context.clear();
        }
    }
}

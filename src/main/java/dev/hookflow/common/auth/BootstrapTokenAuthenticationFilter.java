package dev.hookflow.common.auth;

import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.web.ApiErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Bridges the machine credential into the Spring Security context.
 *
 * <p>{@code /v1/admin/**} has two callers: a human in the console with a session,
 * and a script or a Helm smoke test with {@code X-Bootstrap-Token}. The console
 * path is handled by {@code formLogin}; this filter handles the second one so the
 * controllers no longer need their own {@code @RequestHeader} argument and
 * {@code BootstrapTokenGuard} call. Both paths end up as an {@code Authentication}
 * and are authorized by one role matrix instead of two ad-hoc checks.
 *
 * <p>It is deliberately not a {@code @Component}: a {@code Filter} bean would be
 * auto-registered on every request in addition to running inside the security
 * chain. The security config constructs it directly.
 *
 * <p>A bad token is rejected here (403, the same answer the old controller guard
 * gave) instead of falling through to "anonymous" and producing a misleading 401.
 */
public class BootstrapTokenAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Bootstrap-Token";
    public static final String PRINCIPAL = "bootstrap";

    private final BootstrapTokenGuard guard;
    private final ApiErrorWriter errorWriter;

    public BootstrapTokenAuthenticationFilter(BootstrapTokenGuard guard, ApiErrorWriter errorWriter) {
        this.guard = guard;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (token == null || token.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        try {
            guard.verify(token);
        } catch (ApiException e) {
            errorWriter.write(request, response, e.errorCode(), e.getMessage());
            return;
        }
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        PRINCIPAL, null, List.of(new SimpleGrantedAuthority("ROLE_" + Roles.ADMIN)));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }
}

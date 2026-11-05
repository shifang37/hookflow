package dev.hookflow.console;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two read-only facts the SPA needs before it can render a route.
 *
 * <p>{@code GET /api/auth/session} answers for both states: anonymous is a normal
 * 200 with {@code authenticated:false}, not a 401. That is what lets the login page
 * load without a redirect loop, and it keeps the client's "am I logged in" check
 * separate from its "did this API call fail" handling.
 *
 * <p>The {@link CsrfToken} argument is not decoration: resolving it forces Spring
 * Security's deferred CSRF token to materialise and write the {@code XSRF-TOKEN}
 * cookie. Without that, a fresh browser's first {@code POST /api/auth/login}
 * would fail the CSRF check because the cookie did not exist yet.
 *
 * <p>Login and logout are not controller methods: they are
 * {@code UsernamePasswordAuthenticationFilter} and {@code LogoutFilter} URLs, so
 * the session-fixation and CSRF-logout handling stays framework-owned.
 */
@RestController
@RequestMapping("/api/auth")
@Profile({"api", "all"})
public class ConsoleAuthController {

    @GetMapping("/session")
    public SessionResponse session(CsrfToken csrfToken) {
        // Materialise the token (and therefore the readable cookie) on the first call.
        csrfToken.getToken();
        return SessionResponse.of(SecurityContextHolder.getContext().getAuthentication());
    }
}

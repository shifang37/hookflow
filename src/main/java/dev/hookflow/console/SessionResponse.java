package dev.hookflow.console;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.List;

/**
 * What the SPA needs to know about the current operator: whether there is one, who
 * it is, and which role buttons to show.
 *
 * <p>No token, session id or password hash is ever part of this payload. The
 * session lives in an {@code HttpOnly} cookie; the JavaScript can only ask "am I
 * still logged in", not read the credential that proves it.
 */
public record SessionResponse(boolean authenticated, String username, List<String> roles) {

    public SessionResponse {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public static SessionResponse anonymous() {
        return new SessionResponse(false, null, List.of());
    }

    public static SessionResponse of(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return anonymous();
        }
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .sorted()
                .toList();
        return new SessionResponse(true, authentication.getName(), roles);
    }
}

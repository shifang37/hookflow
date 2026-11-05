package dev.hookflow.console;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Console accounts, bound from {@code hookflow.console.*}.
 *
 * <p>The console has its own trust domain: a human logs in with a username and a
 * password, gets a server-side session and acts with one of two roles. This is
 * deliberately separate from {@link dev.hookflow.config.HookFlowProperties.Admin},
 * which holds the machine-to-machine bootstrap token used by scripts and the Helm
 * smoke test. Keeping the two apart is what lets the public API stay stateless
 * (ADR-0007) while the UI gets a real login (ADR-0015).
 *
 * <p><b>Scope boundary.</b> Accounts are in memory on purpose. A production
 * deployment needs a user table, a password-reset flow and account lockout; this
 * project's acceptance criterion is "the console requires login and a role", and
 * pretending that a hard-coded list is an identity provider would be worse than
 * stating the boundary. The list is configurable through environment variables, so
 * the shape of a future {@code UserDetailsService} is already visible.
 *
 * @param users accounts allowed to sign in; at least one {@code ADMIN} is required
 */
@ConfigurationProperties(prefix = "hookflow.console")
public record ConsoleProperties(List<User> users) {

    public ConsoleProperties {
        users = users == null ? List.of() : List.copyOf(users);
    }

    /**
     * @param username stable login name
     * @param password raw password (encoded with BCrypt at startup) or a pre-encoded
     *                 value with an encoder id prefix such as {@code {bcrypt}$2a$...}
     * @param roles    {@code ADMIN} (read/write) or {@code VIEWER} (read only)
     */
    public record User(String username, String password, List<String> roles) {

        public User {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }
}

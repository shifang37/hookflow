package dev.hookflow.console;

import dev.hookflow.common.auth.Roles;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the configured console accounts into a {@link UserDetailsService}.
 *
 * <p>Two properties matter more than the code:
 *
 * <ol>
 *   <li><b>Raw passwords never reach Spring Security.</b> A value like
 *       {@code viewer123} is encoded with BCrypt once, at startup, so the
 *       in-memory database holds the same shape a real user table would. A
 *       deployment can pass {@code {bcrypt}$2a$...} (or {@code {argon2}...})
 *       instead of a plaintext secret.</li>
 *   <li><b>Bad configuration fails at startup, not at the first login.</b>
 *       Duplicate names, an unknown role, or a console with no {@code ADMIN}
 *       all throw here. A login form that cannot let anyone in is a deployment
 *       bug and should be visible in the pod logs, not discovered by the first
 *       operator who tries it.</li>
 * </ol>
 */
public final class ConsoleUsers {

    private static final Set<String> ALLOWED_ROLES = Set.of(Roles.ADMIN, Roles.VIEWER);
    private static final List<String> ALLOWED_ENCODER_PREFIXES =
            List.of("{bcrypt}", "{argon2}", "{pbkdf2}", "{scrypt}");

    private ConsoleUsers() {
    }

    public static UserDetailsService userDetailsService(ConsoleProperties properties,
                                                        PasswordEncoder passwordEncoder) {
        List<ConsoleProperties.User> configured = properties.users();
        if (configured.isEmpty()) {
            throw new IllegalStateException(
                    "hookflow.console.users is empty: the console would have no way to log in");
        }

        Set<String> usernames = new HashSet<>();
        boolean hasAdmin = false;
        List<UserDetails> users = new ArrayList<>(configured.size());
        for (ConsoleProperties.User user : configured) {
            String username = requireText(user.username(), "hookflow.console.users[].username");
            if (!usernames.add(username)) {
                throw new IllegalStateException("duplicate console username: " + username);
            }
            if (user.roles().isEmpty()) {
                throw new IllegalStateException("console user '" + username + "' has no roles");
            }
            for (String role : user.roles()) {
                if (!ALLOWED_ROLES.contains(role)) {
                    throw new IllegalStateException("console user '" + username
                            + "' has unknown role '" + role + "' (allowed: " + ALLOWED_ROLES + ")");
                }
            }
            if (user.roles().contains(Roles.ADMIN)) {
                hasAdmin = true;
            }
            users.add(User.withUsername(username)
                    .password(encodedPassword(user.password(), passwordEncoder))
                    .roles(user.roles().toArray(String[]::new))
                    .build());
        }
        if (!hasAdmin) {
            throw new IllegalStateException(
                    "hookflow.console.users must contain at least one ADMIN account");
        }
        return new InMemoryUserDetailsManager(users);
    }

    /**
     * An unprefixed value is a raw password and is hashed now; a prefixed value is
     * already a hash and is passed through to the delegating encoder.
     */
    static String encodedPassword(String password, PasswordEncoder passwordEncoder) {
        String value = requireText(password, "hookflow.console.users[].password");
        if (value.startsWith("{")) {
            boolean known = ALLOWED_ENCODER_PREFIXES.stream().anyMatch(value::startsWith);
            if (!known) {
                throw new IllegalStateException("console password uses an unsupported encoder: "
                        + value.substring(0, value.indexOf('}') + 1));
            }
            return value;
        }
        // A bare BCrypt hash is a common thing to paste; accept it rather than hashing
        // the hash and locking the account out with a configuration that looks correct.
        if (value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$")) {
            return "{bcrypt}" + value;
        }
        if (value.startsWith("$argon2")) {
            return "{argon2}" + value;
        }
        return passwordEncoder.encode(value);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " must not be blank");
        }
        return value.trim();
    }
}

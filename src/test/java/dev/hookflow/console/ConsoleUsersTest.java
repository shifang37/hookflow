package dev.hookflow.console;

import dev.hookflow.common.auth.Roles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The configuration boundary of the console accounts. These are startup checks, not
 * login checks: a console with no admin, two users with the same name, or an unknown
 * role is a deployment mistake that should fail the process, not a 500 on the first
 * login attempt.
 */
class ConsoleUsersTest {

    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @Test
    @DisplayName("a raw password is hashed at startup and still matches the configured value")
    void rawPasswordIsHashed() {
        UserDetailsService service = ConsoleUsers.userDetailsService(
                new ConsoleProperties(List.of(
                        new ConsoleProperties.User("admin", "admin123", List.of(Roles.ADMIN)))),
                encoder);

        UserDetails user = service.loadUserByUsername("admin");

        assertThat(user.getPassword()).startsWith("{bcrypt}");
        assertThat(encoder.matches("admin123", user.getPassword())).isTrue();
        assertThat(user.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("a pre-encoded password is passed through, not hashed twice")
    void preEncodedPasswordIsAccepted() {
        String encoded = encoder.encode("viewer123");
        ConsoleProperties properties = new ConsoleProperties(List.of(
                new ConsoleProperties.User("admin", "{bcrypt}$2a$10$abcdefghijklmnopqrstuv",
                        List.of(Roles.ADMIN)),
                new ConsoleProperties.User("viewer", encoded, List.of(Roles.VIEWER))));

        UserDetailsService service = ConsoleUsers.userDetailsService(properties, encoder);
        UserDetails viewer = service.loadUserByUsername("viewer");

        // The exact hash survives: hashing the hash would make the configured password
        // unusable while looking like a perfectly valid setup.
        assertThat(viewer.getPassword()).isEqualTo(encoded);
        assertThat(encoder.matches("viewer123", viewer.getPassword())).isTrue();
    }

    @Test
    @DisplayName("a bare bcrypt hash gets its encoder prefix instead of being encoded again")
    void bareBcryptHashIsAccepted() {
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .encode("admin123");
        ConsoleProperties properties = new ConsoleProperties(List.of(
                new ConsoleProperties.User("admin", hash, List.of(Roles.ADMIN))));

        UserDetails admin = ConsoleUsers.userDetailsService(properties, encoder)
                .loadUserByUsername("admin");

        assertThat(admin.getPassword()).isEqualTo("{bcrypt}" + hash);
        assertThat(encoder.matches("admin123", admin.getPassword())).isTrue();
    }

    @Test
    @DisplayName("a console with no ADMIN account fails startup instead of locking operators out")
    void adminIsRequired() {
        ConsoleProperties properties = new ConsoleProperties(List.of(
                new ConsoleProperties.User("viewer", "viewer123", List.of(Roles.VIEWER))));

        assertThatThrownBy(() -> ConsoleUsers.userDetailsService(properties, encoder))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN");
    }

    @Test
    @DisplayName("bad account configuration is rejected with the offending field named")
    void badConfigurationIsRejected() {
        assertThatThrownBy(() -> ConsoleUsers.userDetailsService(new ConsoleProperties(List.of()), encoder))
                .hasMessageContaining("empty");

        assertThatThrownBy(() -> ConsoleUsers.userDetailsService(new ConsoleProperties(List.of(
                new ConsoleProperties.User("admin", "one", List.of(Roles.ADMIN)),
                new ConsoleProperties.User("admin", "two", List.of(Roles.ADMIN)))), encoder))
                .hasMessageContaining("duplicate");

        assertThatThrownBy(() -> ConsoleUsers.userDetailsService(new ConsoleProperties(List.of(
                new ConsoleProperties.User("admin", "one", List.of("SUPERUSER")))), encoder))
                .hasMessageContaining("unknown role");

        assertThatThrownBy(() -> ConsoleUsers.userDetailsService(new ConsoleProperties(List.of(
                new ConsoleProperties.User("admin", " ", List.of(Roles.ADMIN)))), encoder))
                .hasMessageContaining("password");

        assertThatThrownBy(() -> ConsoleUsers.userDetailsService(new ConsoleProperties(List.of(
                new ConsoleProperties.User("admin", "{noop}admin", List.of(Roles.ADMIN)))), encoder))
                .hasMessageContaining("unsupported encoder");
    }
}

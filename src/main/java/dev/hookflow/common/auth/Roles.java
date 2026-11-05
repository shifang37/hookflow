package dev.hookflow.common.auth;

/**
 * The console's role names, in one place.
 *
 * <p>{@code ADMIN} may read and write the operator domain; {@code VIEWER} may only
 * read it. They live in the shared auth package because both the login side
 * ({@code ConsoleUsers}) and the machine-credential side
 * ({@code BootstrapTokenAuthenticationFilter}) need to agree on the spelling: the
 * bootstrap token is an {@code ADMIN}, not a third role.
 */
public final class Roles {

    public static final String ADMIN = "ADMIN";
    public static final String VIEWER = "VIEWER";

    private Roles() {
    }
}

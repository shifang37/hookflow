package dev.hookflow.console;

import dev.hookflow.common.auth.BootstrapTokenAuthenticationFilter;
import dev.hookflow.common.auth.BootstrapTokenGuard;
import dev.hookflow.common.auth.Roles;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.common.web.ApiErrorWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import tools.jackson.databind.ObjectMapper;

/**
 * The console's security boundary.
 *
 * <p>There are three chains, in this order:
 *
 * <ol>
 *   <li>{@code /v1/admin/**} — an API, not a page. Both a console session and the
 *       {@code X-Bootstrap-Token} machine credential authenticate here, and one
 *       role matrix decides what they may do: {@code ADMIN} reads and writes,
 *       {@code VIEWER} reads. Sessions carry a CSRF token; requests that present
 *       the bootstrap header do not (a custom header is not something a cross-site
 *       form can set, and scripts already hold a bearer-like secret).</li>
 *   <li>{@code /ui/**} and {@code /api/auth/**} — form login, a server-side
 *       session and the SPA's session probe. The login page and static assets are
 *       public; everything else is authenticated.</li>
 *   <li>everything else — the public {@code /v1/**} API, actuator, the demo
 *       console. {@code /v1/**} is authenticated by the hand-rolled
 *       {@code ApiKeyAuthFilter} that runs before Spring Security (ADR-0007);
 *       adding session/CSRF here would break every SDK client for no gain.</li>
 * </ol>
 *
 * <p><b>Why profiles.</b> Relay- and worker-only deployments still expose
 * actuator endpoints, so they get a fourth, permit-all chain in
 * {@link ServiceSecurityConfig}; otherwise Boot's default security would put a
 * generated password in front of the liveness probe. Unit/IT contexts that are
 * not servlet web applications skip this config entirely.
 */
@Configuration
@EnableWebSecurity
@Profile({"api", "all"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ConsoleSecurityConfig {

    private final ConsoleProperties consoleProperties;
    private final BootstrapTokenGuard bootstrapTokenGuard;
    private final ApiErrorWriter errorWriter;
    private final ObjectMapper objectMapper;

    public ConsoleSecurityConfig(ConsoleProperties consoleProperties,
                                 BootstrapTokenGuard bootstrapTokenGuard,
                                 ApiErrorWriter errorWriter,
                                 ObjectMapper objectMapper) {
        this.consoleProperties = consoleProperties;
        this.bootstrapTokenGuard = bootstrapTokenGuard;
        this.errorWriter = errorWriter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder consolePasswordEncoder() {
        // Delegating encoder: raw configured passwords are hashed with BCrypt, and a
        // deployment may pass {bcrypt}/{argon2} values without re-hashing them.
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService consoleUserDetailsService(PasswordEncoder consolePasswordEncoder) {
        return ConsoleUsers.userDetailsService(consoleProperties, consolePasswordEncoder);
    }

    @Bean
    public AuthenticationManager consoleAuthenticationManager(UserDetailsService consoleUserDetailsService,
                                                              PasswordEncoder consolePasswordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(consoleUserDetailsService);
        provider.setPasswordEncoder(consolePasswordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    @Order(1)
    public SecurityFilterChain adminApiSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/v1/admin/**")
                // spa(): the cookie is readable by JavaScript and the raw cookie value is
                // accepted in X-XSRF-TOKEN. Scripts that present the bootstrap header are
                // exempt: a cross-site form cannot set a custom header, and a script already
                // holds the secret the header proves.
                .csrf(csrf -> csrf.spa().ignoringRequestMatchers(
                        new RequestHeaderRequestMatcher(BootstrapTokenAuthenticationFilter.HEADER)))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/v1/admin/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/admin/**").hasAnyRole(Roles.ADMIN, Roles.VIEWER)
                        .requestMatchers(HttpMethod.HEAD, "/v1/admin/**").hasAnyRole(Roles.ADMIN, Roles.VIEWER)
                        .anyRequest().hasRole(Roles.ADMIN))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(apiEntryPoint())
                        .accessDeniedHandler(apiAccessDeniedHandler()))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(new BootstrapTokenAuthenticationFilter(bootstrapTokenGuard, errorWriter),
                        AnonymousAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain consoleSecurity(HttpSecurity http,
                                               AuthenticationManager consoleAuthenticationManager) throws Exception {
        ConsoleLoginHandler loginHandler = new ConsoleLoginHandler(objectMapper, errorWriter);
        http.securityMatcher("/ui", "/ui/**", "/api/auth/**")
                .csrf(csrf -> csrf.spa())
                .authorizeHttpRequests(auth -> auth
                        // The shell and its chunks must load before a session exists; the
                        // login route is a client-side page, not a server-rendered form.
                        // index.html is the forward target of every client route, including
                        // /ui/login; if the container re-dispatches FORWARD through this chain,
                        // the shell itself must not require a session or the page loops.
                        .requestMatchers("/ui/login", "/ui/index.html", "/ui/assets/**", "/ui/favicon.ico",
                                "/api/auth/session", "/api/auth/login").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/ui/login")
                        .loginProcessingUrl("/api/auth/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .successHandler(loginHandler)
                        .failureHandler(loginHandler)
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                        .deleteCookies("JSESSIONID")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/ui/login"))
                        // A rejected CSRF token on /api/auth/logout is still an API call:
                        // answer JSON, not the default empty 403.
                        .accessDeniedHandler(apiAccessDeniedHandler()))
                .authenticationManager(consoleAuthenticationManager);
        return http.build();
    }

    /**
     * The public API and the other HTTP surfaces. This chain must not impose a
     * session or CSRF on {@code /v1/**}: those calls are authenticated by
     * {@code ApiKeyAuthFilter} and are stateless by design.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain publicSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    private AuthenticationEntryPoint apiEntryPoint() {
        return (request, response, exception) ->
                errorWriter.write(request, response, ErrorCode.UNAUTHORIZED, "Authentication required");
    }

    private AccessDeniedHandler apiAccessDeniedHandler() {
        return (request, response, exception) -> {
            boolean csrf = exception instanceof InvalidCsrfTokenException
                    || exception instanceof MissingCsrfTokenException;
            String message = csrf
                    ? "Missing or invalid CSRF token"
                    : "This role is not allowed to perform that operation";
            errorWriter.write(request, response, ErrorCode.FORBIDDEN, message);
        };
    }
}

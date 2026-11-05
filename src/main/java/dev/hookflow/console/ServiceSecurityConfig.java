package dev.hookflow.console;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Relay and worker processes expose actuator, not the console.
 *
 * <p>They still get a chain because adding {@code spring-boot-starter-security}
 * would otherwise let Boot's default configuration put a generated password in
 * front of {@code /actuator/health/readiness} and break the Kubernetes probes.
 * Neither role has a controller under {@code /v1/**} or {@code /ui/**} (both are
 * profile-gated), so permit-all here grants nothing that a client could reach.
 */
@Configuration
@EnableWebSecurity
@Profile({"relay", "worker"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ServiceSecurityConfig {

    @Bean
    public SecurityFilterChain serviceSecurity(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }
}

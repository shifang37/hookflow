package dev.hookflow.console;

import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.common.web.ApiErrorWriter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JSON in, JSON out for the SPA's form login.
 *
 * <p>The default success handler redirects to the saved request. That is correct
 * for a server-rendered form and wrong for an XHR: axios would follow the 302 and
 * hand React an HTML page to parse. Returning the {@link SessionResponse} lets the
 * login page update the session cache and choose the route itself.
 *
 * <p>Every failure is the same 401 and the same sentence. "No such user" and "wrong
 * password" are separate facts only an attacker benefits from; Spring Security's
 * provider already hides the distinction, and this handler keeps it that way.
 */
public class ConsoleLoginHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private final ObjectMapper objectMapper;
    private final ApiErrorWriter errorWriter;

    public ConsoleLoginHandler(ObjectMapper objectMapper, ApiErrorWriter errorWriter) {
        this.objectMapper = objectMapper;
        this.errorWriter = errorWriter;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), SessionResponse.of(authentication));
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        errorWriter.write(request, response, ErrorCode.UNAUTHORIZED, "Invalid username or password");
    }
}

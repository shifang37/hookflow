package dev.hookflow.console;

import dev.hookflow.common.web.ApiErrorWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleLoginHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConsoleLoginHandler handler =
            new ConsoleLoginHandler(objectMapper, new ApiErrorWriter(objectMapper));

    @Test
    @DisplayName("a successful login answers JSON with the identity and roles, not a redirect")
    void successAnswersJson() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        handler.onAuthenticationSuccess(new MockHttpServletRequest("POST", "/api/auth/login"),
                response, authentication);

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("authenticated").asBoolean()).isTrue();
        assertThat(body.get("username").asString()).isEqualTo("admin");
        assertThat(body.get("roles").toString()).contains("ADMIN");
        assertThat(body.has("password")).isFalse();
        assertThat(body.has("sessionId")).isFalse();
    }

    @Test
    @DisplayName("a failed login is one 401 in the shared error shape, with no hint about which part failed")
    void failureAnswersStandardError() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest("POST", "/api/auth/login"),
                response, new BadCredentialsException("bad password for admin"));

        assertThat(response.getStatus()).isEqualTo(401);
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(40100);
        assertThat(body.get("error").asString()).isEqualTo("UNAUTHORIZED");
        assertThat(body.get("message").asString()).isEqualTo("Invalid username or password");
        // The provider's message named the account; the response must not.
        assertThat(response.getContentAsString()).doesNotContain("admin");
    }
}

package dev.hookflow.common.auth;

import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.common.web.ApiErrorWriter;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class BootstrapTokenAuthenticationFilterTest {

    private final BootstrapTokenGuard guard = mock(BootstrapTokenGuard.class);
    private final ApiErrorWriter errorWriter = mock(ApiErrorWriter.class);
    private final BootstrapTokenAuthenticationFilter filter =
            new BootstrapTokenAuthenticationFilter(guard, errorWriter);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("no header means no authentication, and the rest of the chain still runs")
    void noHeaderContinuesAnonymously() throws ServletException, IOException {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/v1/admin/tenants"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(guard, never()).verify(any());
    }

    @Test
    @DisplayName("a valid token becomes an ADMIN authentication")
    void validTokenBecomesAdmin() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/tenants");
        request.addHeader(BootstrapTokenAuthenticationFilter.HEADER, "correct");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo(BootstrapTokenAuthenticationFilter.PRINCIPAL);
        assertThat(authentication.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("an invalid token is a 403 written through the standard error body, not a 401")
    void invalidTokenIsForbidden() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/tenants");
        request.addHeader(BootstrapTokenAuthenticationFilter.HEADER, "wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        doThrow(new ApiException(ErrorCode.FORBIDDEN, "Invalid bootstrap token"))
                .when(guard).verify("wrong");

        filter.doFilter(request, response, chain);

        verify(errorWriter).write(eq(request), eq(response), eq(ErrorCode.FORBIDDEN),
                eq("Invalid bootstrap token"));
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}

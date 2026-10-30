package dev.hookflow.api;

import dev.hookflow.common.auth.ApiKeyPrincipal;
import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.service.EndpointService;
import dev.hookflow.service.EndpointTestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The probe endpoint's own contract: it needs the write scope and maps the service outcome
 * one-to-one. The HTTP call and the "no rows written" guarantee are covered by
 * {@code EndpointTestIT}.
 */
class EndpointControllerTest {

    private final EndpointService endpointService = mock(EndpointService.class);
    private final EndpointTestService endpointTestService = mock(EndpointTestService.class);
    private final EndpointController controller =
            new EndpointController(endpointService, endpointTestService);

    @AfterEach
    void clearPrincipal() {
        ApiKeyPrincipal.Context.clear();
    }

    @Test
    @DisplayName("a probe maps the deliverer outcome into the response")
    void mapsOutcome() {
        ApiKeyPrincipal.Context.set(new ApiKeyPrincipal(7L, "it-key", "endpoint:write"));
        when(endpointTestService.test(7L, 3L, Map.of("orderId", 1)))
                .thenReturn(new EndpointTestService.TestOutcome(
                        true, false, 200, null, 42, "{\"ok\":true}"));

        var response = controller.test(3L, Map.of("orderId", 1));

        assertThat(response.endpointId()).isEqualTo(3L);
        assertThat(response.success()).isTrue();
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.durationMs()).isEqualTo(42);
        assertThat(response.responsePreview()).isEqualTo("{\"ok\":true}");
    }

    @Test
    @DisplayName("a tenant key without endpoint:write cannot probe a customer URL")
    void requiresWriteScope() {
        ApiKeyPrincipal.Context.set(new ApiKeyPrincipal(7L, "it-key", "event:write"));

        assertThatThrownBy(() -> controller.test(3L, Map.of()))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).errorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(endpointTestService);
    }
}

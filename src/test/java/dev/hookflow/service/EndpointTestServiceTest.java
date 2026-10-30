package dev.hookflow.service;

import dev.hookflow.common.error.ApiException;
import dev.hookflow.common.error.ErrorCode;
import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.domain.endpoint.Endpoint;
import dev.hookflow.worker.HttpDeliverer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The probe's contract: use the endpoint's real signing path, write nothing, and do not let
 * one console user hammer a customer's receiver.
 */
class EndpointTestServiceTest {

    private static final long TENANT = 1L;
    private static final long ENDPOINT = 3L;

    private final EndpointService endpointService = mock(EndpointService.class);
    private final EgressGuard egressGuard = mock(EgressGuard.class);
    private final HttpDeliverer httpDeliverer = mock(HttpDeliverer.class);
    private final ObjectMapper objectMapper = mock(ObjectMapper.class);
    private final HookFlowProperties properties = new HookFlowProperties(
            new HookFlowProperties.Delivery(1_000, 1_000, 512, 65_536),
            null, null, null, null, null, null, null, null, null, null, null);
    private final EndpointTestService service = new EndpointTestService(
            endpointService, egressGuard, httpDeliverer, objectMapper, properties);

    @Test
    @DisplayName("sends a synthetic event through the real deliverer and returns its outcome")
    void sendsProbe() {
        when(endpointService.require(TENANT, ENDPOINT)).thenReturn(endpoint());
        when(objectMapper.writeValueAsString(anyMap())).thenReturn("{\"order\":1}");
        when(httpDeliverer.deliver(any(), any(), any(), eq(1)))
                .thenReturn(new HttpDeliverer.Outcome(true, false, 200, null, "{\"ok\":true}", 42));

        EndpointTestService.TestOutcome outcome =
                service.test(TENANT, ENDPOINT, Map.of("order", 1));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.statusCode()).isEqualTo(200);
        assertThat(outcome.durationMs()).isEqualTo(42);
        assertThat(outcome.responsePreview()).isEqualTo("{\"ok\":true}");
        verify(egressGuard).validate("https://customer.example/hook");

        ArgumentCaptor<dev.hookflow.domain.event.Event> event =
                ArgumentCaptor.forClass(dev.hookflow.domain.event.Event.class);
        verify(httpDeliverer).deliver(any(), event.capture(), any(), eq(1));
        assertThat(event.getValue().id()).isZero();
        assertThat(event.getValue().eventType()).isEqualTo("endpoint.test");
        assertThat(event.getValue().payload()).isEqualTo("{\"order\":1}");
        assertThat(event.getValue().tenantId()).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("the second probe within a second is a 429 before any outbound call")
    void rateLimits() {
        when(endpointService.require(TENANT, ENDPOINT)).thenReturn(endpoint());
        when(objectMapper.writeValueAsString(anyMap())).thenReturn("{}");
        when(httpDeliverer.deliver(any(), any(), any(), eq(1)))
                .thenReturn(new HttpDeliverer.Outcome(true, false, 200, null, "", 1));

        service.test(TENANT, ENDPOINT, Map.of());

        assertThatThrownBy(() -> service.test(TENANT, ENDPOINT, Map.of()))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).errorCode())
                        .isEqualTo(ErrorCode.RATE_LIMITED));
        verify(httpDeliverer, times(1)).deliver(any(), any(), any(), eq(1));
    }

    @Test
    @DisplayName("a payload over the delivery limit is a 413 and never reaches the receiver")
    void rejectsOversizedPayload() {
        when(endpointService.require(TENANT, ENDPOINT)).thenReturn(endpoint());
        when(objectMapper.writeValueAsString(anyMap())).thenReturn("x".repeat(65_537));

        assertThatThrownBy(() -> service.test(TENANT, ENDPOINT, Map.of("big", true)))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).errorCode())
                        .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
        verifyNoInteractions(httpDeliverer);
    }

    private static Endpoint endpoint() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 29, 8, 0);
        return new Endpoint(ENDPOINT, TENANT, "https://customer.example/hook",
                new byte[]{1, 2, 3}, (short) 1, "*", 8, 0, Endpoint.ACTIVE, now, now);
    }
}

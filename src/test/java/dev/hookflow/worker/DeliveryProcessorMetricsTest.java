package dev.hookflow.worker;

import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.domain.delivery.Delivery;
import dev.hookflow.domain.delivery.DeliveryStatus;
import dev.hookflow.domain.endpoint.Endpoint;
import dev.hookflow.domain.event.Event;
import dev.hookflow.fairness.AdmissionControl;
import dev.hookflow.messaging.DeliveryMessage;
import dev.hookflow.persistence.DeliveryAttemptMapper;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.persistence.EndpointMapper;
import dev.hookflow.persistence.EventMapper;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two metrics task G adds, asserted through a real Prometheus registry so the
 * exported name is covered too: {@code hookflow_delivery_attempted_total} (the
 * success-rate denominator) and the histogram behind
 * {@code hookflow_delivery_duration_seconds_bucket}.
 */
class DeliveryProcessorMetricsTest {

    private static final long DELIVERY_ID = 1L;
    private static final long TENANT_ID = 2L;
    private static final long EVENT_ID = 50L;
    private static final long ENDPOINT_ID = 9L;

    private final DeliveryMapper deliveryMapper = mock(DeliveryMapper.class);
    private final DeliveryAttemptMapper attemptMapper = mock(DeliveryAttemptMapper.class);
    private final EventMapper eventMapper = mock(EventMapper.class);
    private final EndpointMapper endpointMapper = mock(EndpointMapper.class);
    private final HttpDeliverer deliverer = mock(HttpDeliverer.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final AdmissionControl admission = mock(AdmissionControl.class);
    private final HookFlowProperties properties = new HookFlowProperties(
            null, null, null, new HookFlowProperties.Retry(100, 10_000, 8), null, null,
            null, null, null, null, null, null);
    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    private DeliveryProcessor processor;
    private Delivery delivery;

    @BeforeEach
    void setUp() {
        delivery = new Delivery(DELIVERY_ID, TENANT_ID, EVENT_ID, ENDPOINT_ID,
                DeliveryStatus.DELIVERING, 0, 8, null, null, null, null, null,
                LocalDateTime.now(), LocalDateTime.now());
        Event event = new Event(EVENT_ID, TENANT_ID, "order.paid", "{\"amount\":42}", "key", LocalDateTime.now());

        when(deliveryMapper.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));
        when(eventMapper.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(endpointMapper.findById(ENDPOINT_ID)).thenReturn(Optional.of(endpoint(Endpoint.ACTIVE)));
        when(admission.admit(anyLong(), anyLong())).thenReturn(true);
        when(transactionTemplate.execute(any())).thenReturn(1);

        doAnswer(invocation -> {
            Consumer<?> work = invocation.getArgument(0);
            work.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        processor = new DeliveryProcessor(deliveryMapper, attemptMapper, eventMapper, endpointMapper,
                deliverer, transactionTemplate, properties, registry, admission);
    }

    @Test
    void recordsOneAttemptAndTheDurationHistogramOnSuccess() {
        when(deliverer.deliver(any(), any(), any(), anyInt()))
                .thenReturn(new HttpDeliverer.Outcome(true, false, 200, null, "ok", 250));

        assertThat(processor.process(message())).isTrue();

        assertThat(registry.get("hookflow.delivery.attempted").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("hookflow.delivery.succeeded").counter().count()).isEqualTo(1.0);

        assertThat(registry.get("hookflow.delivery.duration").timer().count()).isEqualTo(1);
        assertThat(registry.get("hookflow.delivery.duration").timer()
                .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isCloseTo(250.0, within(1.0));

        String scrape = registry.scrape();
        assertThat(scrape).contains("hookflow_delivery_attempted_total");
        assertThat(scrape).contains("hookflow_delivery_duration_seconds_bucket");
        assertThat(scrape).contains("hookflow_delivery_succeeded_total");
    }

    @Test
    void recordsTheAttemptAndDurationForARetryableFailure() {
        when(deliverer.deliver(any(), any(), any(), anyInt()))
                .thenReturn(new HttpDeliverer.Outcome(false, true, 500, "SERVER_5XX", "boom", 120));

        assertThat(processor.process(message())).isTrue();

        assertThat(registry.get("hookflow.delivery.attempted").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("hookflow.delivery.retry_scheduled").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("hookflow.delivery.duration").timer().count()).isEqualTo(1);

        String scrape = registry.scrape();
        assertThat(scrape).contains("hookflow_delivery_retry_scheduled_total");
        assertThat(scrape).contains("hookflow_delivery_duration_seconds_bucket");
    }

    @Test
    void doesNotCountAClaimedAttemptThatNeverReachesTheNetwork() {
        when(endpointMapper.findById(ENDPOINT_ID)).thenReturn(Optional.of(endpoint(Endpoint.PAUSED)));

        assertThat(processor.process(message())).isTrue();

        assertThat(registry.find("hookflow.delivery.attempted").counter()).isNull();
        assertThat(registry.get("hookflow.delivery.duration").timer().count()).isZero();
        verify(deliveryMapper).markCanceled(eq(DELIVERY_ID), anyString());
    }

    private static DeliveryMessage message() {
        return new DeliveryMessage(DELIVERY_ID, 1, TENANT_ID, EVENT_ID, ENDPOINT_ID, null);
    }

    private static Endpoint endpoint(String status) {
        return new Endpoint(ENDPOINT_ID, TENANT_ID, "https://customer.example/hook",
                new byte[0], (short) 1, "*", 8, 0, status, LocalDateTime.now(), LocalDateTime.now());
    }
}

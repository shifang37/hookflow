package dev.hookflow.sdk;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HookFlowRetryTest {

    private MockWebServer server;
    private RecordingSleeper sleeper;
    private HookFlowClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        sleeper = new RecordingSleeper();
        client = clientWith(RetryPolicy.defaults());
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
        server.shutdown();
    }

    private HookFlowClient clientWith(RetryPolicy policy) {
        return HookFlowClient.builder()
                .apiKey("hk_test")
                .baseUrl(server.url("/").toString())
                .retryPolicy(policy)
                .sleeper(sleeper)
                .build();
    }

    private static MockResponse accepted() {
        return new MockResponse().setResponseCode(202)
                .setBody("{\"eventId\":1,\"deliveryCount\":0,\"idempotentReplay\":false}");
    }

    @Test
    void retriesARateLimitAndHonorsRetryAfter() {
        server.enqueue(new MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "2")
                .setBody("{\"code\":42900,\"error\":\"RATE_LIMITED\"}"));
        server.enqueue(accepted());

        EventResult result = client.sendEvent("order.paid", Map.of("orderId", 1));

        assertThat(result.getEventId()).isEqualTo(1);
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(sleeper.delays()).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void givesUpAfterTheConfiguredAttemptsAndKeepsTheStatus() {
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse().setResponseCode(503)
                    // A nonzero value keeps OkHttp from following the 503 itself; the
                    // injected sleeper means nothing actually waits.
                    .setHeader("Retry-After", "1")
                    .setBody("{\"code\":50301,\"error\":\"OVER_CAPACITY\",\"requestId\":\"req-503\"}"));
        }

        assertThatThrownBy(() -> client.sendEvent("order.paid", Map.of("orderId", 1)))
                .isInstanceOfSatisfying(HookFlowException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(503);
                    assertThat(e.getCode()).isEqualTo(50301);
                    assertThat(e.getErrorName()).isEqualTo("OVER_CAPACITY");
                    assertThat(e.getRequestId()).isEqualTo("req-503");
                    assertThat(e.isRetryable()).isTrue();
                });

        assertThat(server.getRequestCount()).isEqualTo(3);
        assertThat(sleeper.delays()).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    @Test
    void doesNotRetryAValidationFailure() {
        server.enqueue(new MockResponse().setResponseCode(400)
                .setBody("{\"code\":40000,\"error\":\"VALIDATION_FAILED\",\"message\":\"bad\","
                        + "\"requestId\":\"req-400\"}"));

        assertThatThrownBy(() -> client.sendEvent("order.paid", Map.of("orderId", 1)))
                .isInstanceOfSatisfying(HookFlowException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(400);
                    assertThat(e.getCode()).isEqualTo(40000);
                    assertThat(e.getRequestId()).isEqualTo("req-400");
                    assertThat(e.isRetryable()).isFalse();
                });

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(sleeper.delays()).isEmpty();
    }

    @Test
    void doesNotRetryAnIdempotencyKeyConflict() {
        server.enqueue(new MockResponse().setResponseCode(409)
                .setBody("{\"code\":40901,\"error\":\"IDEMPOTENCY_KEY_REUSED\","
                        + "\"requestId\":\"req-409\"}"));

        assertThatThrownBy(() -> client.sendEvent("order.paid", Map.of("orderId", 1)))
                .isInstanceOfSatisfying(HookFlowException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo(40901);
                    assertThat(e.getErrorName()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                });

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void retriesATransportFailure() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        server.enqueue(accepted());

        EventResult result = client.sendEvent("order.paid", Map.of("orderId", 1));

        assertThat(result.getEventId()).isEqualTo(1);
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(sleeper.delays()).hasSize(1);
    }

    @Test
    void wrapsAnExhaustedTransportFailureWithStatusZero() {
        try (HookFlowClient shortClient = clientWith(RetryPolicy.builder().maxAttempts(2).build())) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

            assertThatThrownBy(() -> shortClient.sendEvent("order.paid", Map.of("orderId", 1)))
                    .isInstanceOfSatisfying(HookFlowException.class, e -> {
                        assertThat(e.getStatusCode()).isZero();
                        assertThat(e.getCode()).isZero();
                        assertThat(e.getCause()).isInstanceOf(IOException.class);
                        assertThat(e.isRetryable()).isTrue();
                    });

            assertThat(server.getRequestCount()).isEqualTo(2);
            assertThat(sleeper.delays()).hasSize(1);
        }
    }

    @Test
    void appliesExponentialBackoffWhenTheServerSendsNoHint() {
        try (HookFlowClient backoffClient = clientWith(RetryPolicy.builder()
                .maxAttempts(4)
                .baseDelay(Duration.ofMillis(100))
                .maxDelay(Duration.ofSeconds(10))
                .jitter(0)
                .build())) {
            for (int i = 0; i < 4; i++) {
                server.enqueue(new MockResponse().setResponseCode(500).setBody("{}"));
            }

            assertThatThrownBy(() -> backoffClient.sendEvent("order.paid", Map.of("orderId", 1)))
                    .isInstanceOf(HookFlowException.class);

            assertThat(sleeper.delays()).containsExactly(
                    Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(400));
            assertThat(server.getRequestCount()).isEqualTo(4);
        }
    }

    @Test
    void capsRetryAfterAtThePolicyMaximum() {
        try (HookFlowClient cappedClient = clientWith(RetryPolicy.builder()
                .maxAttempts(2)
                .maxDelay(Duration.ofSeconds(1))
                .build())) {
            server.enqueue(new MockResponse().setResponseCode(429)
                    .setHeader("Retry-After", "60")
                    .setBody("{\"code\":42900,\"error\":\"RATE_LIMITED\"}"));
            server.enqueue(accepted());

            cappedClient.sendEvent("order.paid", Map.of("orderId", 1));

            assertThat(sleeper.delays()).containsExactly(Duration.ofSeconds(1));
        }
    }

    @Test
    void fallsBackToExponentialBackoffWhenRetryAfterIsNotASecondCount() {
        server.enqueue(new MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "Wed, 21 Oct 2015 07:28:00 GMT")
                .setBody("{}"));
        server.enqueue(accepted());

        client.sendEvent("order.paid", Map.of("orderId", 1));

        assertThat(sleeper.delays()).hasSize(1);
        assertThat(sleeper.delays().get(0)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void canDisableRetryAfter() {
        try (HookFlowClient hintless = clientWith(RetryPolicy.builder()
                .maxAttempts(2)
                .baseDelay(Duration.ofMillis(10))
                .jitter(0)
                .honorRetryAfter(false)
                .build())) {
            server.enqueue(new MockResponse().setResponseCode(429)
                    .setHeader("Retry-After", "5")
                    .setBody("{}"));
            server.enqueue(accepted());

            hintless.sendEvent("order.paid", Map.of("orderId", 1));

            assertThat(sleeper.delays()).containsExactly(Duration.ofMillis(10));
        }
    }

    @Test
    void maxAttemptsOneMeansNoRetry() {
        try (HookFlowClient singleShot = clientWith(RetryPolicy.builder().maxAttempts(1).build())) {
            server.enqueue(new MockResponse().setResponseCode(503).setBody("{}"));

            assertThatThrownBy(() -> singleShot.sendEvent("order.paid", Map.of("orderId", 1)))
                    .isInstanceOfSatisfying(HookFlowException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(503));

            assertThat(server.getRequestCount()).isEqualTo(1);
            assertThat(sleeper.delays()).isEmpty();
        }
    }

    @Test
    void restoresTheInterruptFlagAndAbortsWhenTheSleeperIsInterrupted() {
        Sleeper interrupting = duration -> {
            throw new InterruptedException("test stop");
        };
        try (HookFlowClient interrupted = HookFlowClient.builder()
                .apiKey("hk_test")
                .baseUrl(server.url("/").toString())
                .sleeper(interrupting)
                .build()) {
            server.enqueue(new MockResponse().setResponseCode(500).setBody("{}"));

            assertThatThrownBy(() -> interrupted.sendEvent("order.paid", Map.of("orderId", 1)))
                    .isInstanceOfSatisfying(HookFlowException.class,
                            e -> assertThat(e.getCause()).isInstanceOf(InterruptedException.class));

            assertThat(Thread.interrupted()).isTrue();
        }
    }

    @Test
    void jitterKeepsTheDelayWithinTheConfiguredBand() {
        try (HookFlowClient jittered = clientWith(RetryPolicy.builder()
                .maxAttempts(2)
                .baseDelay(Duration.ofMillis(100))
                .jitter(0.5)
                .build())) {
            server.enqueue(new MockResponse().setResponseCode(500).setBody("{}"));
            server.enqueue(accepted());

            jittered.sendEvent("order.paid", Map.of("orderId", 1));

            assertThat(sleeper.delays()).hasSize(1);
            assertThat(sleeper.delays().get(0))
                    .isBetween(Duration.ofMillis(50), Duration.ofMillis(150));
        }
    }
}

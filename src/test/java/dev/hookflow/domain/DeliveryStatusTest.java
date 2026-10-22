package dev.hookflow.domain;

import dev.hookflow.domain.delivery.DeliveryStatus;
import dev.hookflow.domain.endpoint.Endpoint;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The state machine is documented in {@link DeliveryStatus}; these assertions keep
 * the documentation and the code from drifting apart.
 */
class DeliveryStatusTest {

    @Test
    void terminalStatesAreExactlyTheOnesWithNoOutgoingTransition() {
        assertThat(DeliveryStatus.SUCCEEDED.isTerminal()).isTrue();
        assertThat(DeliveryStatus.DEAD.isTerminal()).isTrue();
        assertThat(DeliveryStatus.CANCELED.isTerminal()).isTrue();
        assertThat(DeliveryStatus.PENDING.isTerminal()).isFalse();
        assertThat(DeliveryStatus.DELIVERING.isTerminal()).isFalse();
        assertThat(DeliveryStatus.RETRY_WAIT.isTerminal()).isFalse();
    }

    @Test
    void onlyPendingAndRetryWaitCanBeClaimed() {
        assertThat(DeliveryStatus.PENDING.isClaimable()).isTrue();
        assertThat(DeliveryStatus.RETRY_WAIT.isClaimable()).isTrue();
        assertThat(DeliveryStatus.DELIVERING.isClaimable()).isFalse();
        assertThat(DeliveryStatus.DEAD.isClaimable()).isFalse();
        assertThat(DeliveryStatus.SUCCEEDED.isClaimable()).isFalse();
        assertThat(DeliveryStatus.CANCELED.isClaimable()).isFalse();
    }

    @Test
    void wildcardEndpointReceivesEverything() {
        assertThat(endpoint("*").subscribesTo("order.paid")).isTrue();
        assertThat(endpoint("*").subscribesTo("anything.at.all")).isTrue();
    }

    @Test
    void subscriptionListIsMatchedExactlyNotByPrefix() {
        Endpoint endpoint = endpoint("order.paid, order.refunded");
        assertThat(endpoint.subscribesTo("order.paid")).isTrue();
        assertThat(endpoint.subscribesTo("order.refunded")).isTrue();
        assertThat(endpoint.subscribesTo("order")).isFalse();
        assertThat(endpoint.subscribesTo("order.paid.extra")).isFalse();
        assertThat(endpoint.subscribesTo("ORDER.PAID")).isFalse();
    }

    @Test
    void endpointWithoutSubscriptionsReceivesNothing() {
        assertThat(endpoint("").subscribesTo("order.paid")).isFalse();
        assertThat(endpoint(null).subscribesTo("order.paid")).isFalse();
    }

    private static Endpoint endpoint(String subscribedTypes) {
        return new Endpoint(1L, 1L, "http://localhost:9099/hook", new byte[]{1}, (short) 1,
                subscribedTypes, 8, 0, Endpoint.ACTIVE, null, null);
    }
}

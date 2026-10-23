package dev.hookflow.messaging;

import dev.hookflow.tracing.TraceContextSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * The all-or-nothing rule of the batched publisher.
 *
 * <p>The relay marked a row PUBLISHED per message before v0.4; now it marks a whole
 * batch at once. The risk that introduces is that one unconfirmed message gets swept
 * along with the acknowledged ones, which would silently lose a delivery. These tests
 * pin the opposite: <em>no ack, no publish</em> — the message comes back as
 * {@code FAILED} and the relay puts the row back to NEW.
 *
 * <p>The broker itself is not mocked by behaving like a broker; only the two things
 * that matter are: whether the send throws, and what the confirm says.
 */
class DeliveryPublisherTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final TraceContextSupport traceContext = mock(TraceContextSupport.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final DeliveryPublisher publisher = new DeliveryPublisher(
            rabbitTemplate, objectMapper, traceContext, Duration.ofMillis(200));

    private final List<CorrelationData> sent = new ArrayList<>();

    @BeforeEach
    void recordSends() {
        sent.clear();
    }

    @Test
    @DisplayName("broker unreachable: every message is reported FAILED, none as published")
    void sendFailureFailsTheWholeBatch() {
        doThrow(new AmqpException("channel closed"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(),
                        any(MessagePostProcessor.class), any(CorrelationData.class));

        List<DeliveryPublisher.PublishOutcome> outcomes = publisher.publishAll(messages(3));

        assertThat(outcomes).containsExactly(
                DeliveryPublisher.PublishOutcome.FAILED,
                DeliveryPublisher.PublishOutcome.FAILED,
                DeliveryPublisher.PublishOutcome.FAILED);
    }

    @Test
    @DisplayName("acks: one round trip for the batch, every message ACKED")
    void acksAreReportedPerMessage() {
        completeEachSendWith(true);

        List<DeliveryPublisher.PublishOutcome> outcomes = publisher.publishAll(messages(5));

        assertThat(outcomes).containsOnly(DeliveryPublisher.PublishOutcome.ACKED);
        assertThat(sent).as("all five were sent before any confirm was awaited").hasSize(5);
    }

    @Test
    @DisplayName("a nack is a refusal, not an unknown: reported as NACKED so the row goes back")
    void nackIsReportedAsSuch() {
        completeEachSendWith(false);

        List<DeliveryPublisher.PublishOutcome> outcomes = publisher.publishAll(messages(2));

        assertThat(outcomes).containsExactly(
                DeliveryPublisher.PublishOutcome.NACKED,
                DeliveryPublisher.PublishOutcome.NACKED);
    }

    @Test
    @DisplayName("a confirm that never arrives is FAILED, not a reason to hang the relay")
    void missingConfirmIsFailed() {
        // Send succeeds and the future is simply never completed.
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(4));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(),
                any(MessagePostProcessor.class), any(CorrelationData.class));

        long started = System.nanoTime();
        List<DeliveryPublisher.PublishOutcome> outcomes = publisher.publishAll(messages(1));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(outcomes).containsExactly(DeliveryPublisher.PublishOutcome.FAILED);
        assertThat(elapsedMs)
                .as("one deadline for the batch, and the confirm timeout is 10 s")
                .isLessThan(15_000);
    }

    private void completeEachSendWith(boolean ack) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            sent.add(correlation);
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "queue full"));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    private List<DeliveryMessage> messages(int count) {
        List<DeliveryMessage> messages = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            messages.add(new DeliveryMessage(100 + i, 1, 1L, 2L, 3L, null));
        }
        return messages;
    }
}

package dev.hookflow.messaging;

import dev.hookflow.tracing.TraceContextSupport;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Publishes a delivery work item and blocks until the broker confirms it.
 *
 * <p>The confirm is what makes it safe for the relay to mark the outbox row as
 * published: "confirmed" means the broker has taken responsibility. If the
 * process dies after the confirm but before the row update, the row is
 * re-published later — hence at-least-once.
 */
@Component
public class DeliveryPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeliveryPublisher.class);
    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(10);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final TraceContextSupport traceContext;

    public DeliveryPublisher(RabbitTemplate rabbitTemplate,
                             ObjectMapper objectMapper,
                             TraceContextSupport traceContext) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.traceContext = traceContext;
    }

    /**
     * Publishes and waits for the broker to take responsibility.
     *
     * <p><b>Everything</b> is inside the try, including {@code convertAndSend}: when
     * the broker is unreachable the send itself throws {@code AmqpException}, and an
     * unwrapped exception here would escape the relay's error handling, leaving the
     * outbox row stuck in PUBLISHING until the 60-second sweeper noticed. Wrapping it
     * lets the relay put the row straight back to NEW and retry on the next tick.
     *
     * <p>A <em>nack</em> is a different event: the broker refused the message it
     * received, most often because the queue hit its length cap. That is expected
     * behaviour under overload and is reported as such, instead of looking like a
     * broker outage.
     *
     * @throws PublishFailedException when the message could not be published, for any reason
     */
    public void publish(DeliveryMessage message) {
        String body = objectMapper.writeValueAsString(message);
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());

        Span span = traceContext.startSpan("hookflow.publish", message.traceparent());
        try (Tracer.SpanInScope ignored = traceContext.openScope(span)) {
            rabbitTemplate.convertAndSend("", RabbitTopology.DELIVERY_QUEUE, body,
                    traceHeaders(message), correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(CONFIRM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                throw new PublishFailedException("Broker nacked message " + correlation.getId()
                        + " (queue full?): " + confirm.getReason(), true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishFailedException("Interrupted while awaiting publisher confirm", e);
        } catch (PublishFailedException e) {
            throw e;
        } catch (Exception e) {
            log.debug("publish failed for {}", correlation.getId(), e);
            throw new PublishFailedException(
                    "Publish failed for " + correlation.getId() + ": " + e.getMessage(), e);
        } finally {
            traceContext.end(span);
        }
    }

    /**
     * Broker-side copy of the trace context.
     *
     * <p>It exists so that anything watching the queue (a broker plugin, a
     * dashboard, the next hop's own instrumentation) sees the same trace. The
     * authoritative copy is the one inside the payload.
     */
    private MessagePostProcessor traceHeaders(DeliveryMessage message) {
        return amqpMessage -> {
            if (message.traceparent() != null) {
                amqpMessage.getMessageProperties().setHeader(TraceContextSupport.TRACEPARENT,
                        message.traceparent());
            }
            return amqpMessage;
        };
    }

    public static class PublishFailedException extends RuntimeException {

        private final boolean nacked;

        public PublishFailedException(String message) {
            this(message, false);
        }

        public PublishFailedException(String message, boolean nacked) {
            super(message);
            this.nacked = nacked;
        }

        public PublishFailedException(String message, Throwable cause) {
            super(message, cause);
            this.nacked = false;
            log.debug("publish failed", cause);
        }

        /** @return true when the broker rejected the message, as opposed to being unreachable */
        public boolean nacked() {
            return nacked;
        }
    }
}

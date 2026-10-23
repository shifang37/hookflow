package dev.hookflow.messaging;

import dev.hookflow.tracing.TraceContextSupport;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Publishes a delivery work item and blocks until the broker confirms it.
 *
 * <p>The confirm is what makes it safe for the relay to mark the outbox row as
 * published: "confirmed" means the broker has taken responsibility. If the
 * process dies after the confirm but before the row update, the row is
 * re-published later - hence at-least-once.
 *
 * <h2>v0.4: one round trip per batch, not per row</h2>
 *
 * <p>{@link #publish(DeliveryMessage)} waits for each confirm before sending the
 * next message. That is the clearest code, and it measured at ~24 ms per row on
 * this machine - a hard ceiling near 40 events/s for the whole platform
 * (docs/performance.md section 15). {@link #publishAll} sends every message first
 * and then waits for the confirms together, so N rows cost one round trip instead
 * of N. The semantics do not change: a row is only reported as published when the
 * broker acknowledged <em>that</em> message.
 */
@Component
public class DeliveryPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeliveryPublisher.class);
    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(10);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final TraceContextSupport traceContext;
    private final Duration confirmTimeout;

    // Explicit @Autowired because there are two constructors: without it Spring
    // tries the no-arg one and fails the context at startup (the project's
    // documented rule for multi-constructor beans).
    @Autowired
    public DeliveryPublisher(RabbitTemplate rabbitTemplate,
                             ObjectMapper objectMapper,
                             TraceContextSupport traceContext) {
        this(rabbitTemplate, objectMapper, traceContext, CONFIRM_TIMEOUT);
    }

    /** Test seam: the batch deadline is the one thing a unit test cannot wait out. */
    DeliveryPublisher(RabbitTemplate rabbitTemplate,
                      ObjectMapper objectMapper,
                      TraceContextSupport traceContext,
                      Duration confirmTimeout) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.traceContext = traceContext;
        this.confirmTimeout = confirmTimeout;
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
     * Publishes a batch and waits for the broker to confirm it.
     *
     * <p>Messages go out back to back before any confirm is awaited, so the broker
     * sees one burst and answers with one burst: N rows cost one network round trip
     * (plus the broker's own fsyncs, which it batches) instead of N. That is the
     * difference between ~40 events/s and the four-digit rate this method produced
     * when it was measured (docs/performance.md section 16).
     *
     * <p><b>One deadline for the whole batch</b>, not one timeout per message: a
     * hung broker must not be able to hold the relay for N x 10 s in a single tick.
     *
     * <p>A message whose fate is unknown (send threw, confirm nacked, confirm never
     * arrived) is reported as such and the caller puts the row back to NEW. If the
     * confirm did in fact reach the broker, the re-publish duplicates a delivery -
     * which the platform already promises may happen
     * ({@code docs/delivery-semantics.md}, at-least-once). Reporting a row as
     * published without an ack would be the only unforgivable outcome.
     *
     * @return one outcome per message, in the same order as the input
     */
    public List<PublishOutcome> publishAll(List<DeliveryMessage> messages) {
        List<PublishOutcome> outcomes = new ArrayList<>(
                Collections.nCopies(messages.size(), PublishOutcome.FAILED));
        List<CorrelationData> correlations = new ArrayList<>(messages.size());

        for (DeliveryMessage message : messages) {
            String body = objectMapper.writeValueAsString(message);
            CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
            Span span = traceContext.startSpan("hookflow.publish", message.traceparent());
            try (Tracer.SpanInScope ignored = traceContext.openScope(span)) {
                rabbitTemplate.convertAndSend("", RabbitTopology.DELIVERY_QUEUE, body,
                        traceHeaders(message), correlation);
                correlations.add(correlation);
            } catch (RuntimeException e) {
                // The channel is gone: nothing after this can be sent either. Stop
                // here and let the caller put every unconfirmed row back to NEW.
                log.debug("publish failed after {} of {} messages",
                        correlations.size(), messages.size(), e);
                break;
            } finally {
                traceContext.end(span);
            }
        }

        long deadline = System.nanoTime() + confirmTimeout.toNanos();
        for (int index = 0; index < correlations.size(); index++) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                log.warn("publisher confirms for {} of {} messages did not arrive within {}",
                        correlations.size() - index, messages.size(), confirmTimeout);
                break;
            }
            CorrelationData correlation = correlations.get(index);
            try {
                CorrelationData.Confirm confirm = correlation.getFuture()
                        .get(remainingNanos, TimeUnit.NANOSECONDS);
                outcomes.set(index, confirm.isAck() ? PublishOutcome.ACKED : PublishOutcome.NACKED);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Timeout or a channel error: this message's fate is unknown, and
                // "unknown" has to be treated as "not published".
                log.debug("no confirm for message {}", correlation.getId(), e);
                break;
            }
        }
        return outcomes;
    }

    /** What happened to one message of a batch. */
    public enum PublishOutcome {
        /** The broker acknowledged it; the outbox row may be marked PUBLISHED. */
        ACKED,
        /** The broker refused it (most often: the queue is at its length cap). */
        NACKED,
        /** It was never sent, or its confirm never arrived. Treat as "not published". */
        FAILED
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

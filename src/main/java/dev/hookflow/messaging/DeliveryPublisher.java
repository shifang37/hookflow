package dev.hookflow.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.UUID;

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

    public DeliveryPublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * @throws PublishFailedException when the broker does not confirm in time
     */
    public void publish(DeliveryMessage message) {
        String body = objectMapper.writeValueAsString(message);
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbitTemplate.convertAndSend("", RabbitTopology.DELIVERY_QUEUE, body, correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(CONFIRM_TIMEOUT.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                throw new PublishFailedException("Broker nacked message " + correlation.getId()
                        + ": " + confirm.getReason());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishFailedException("Interrupted while awaiting publisher confirm", e);
        } catch (Exception e) {
            if (e instanceof PublishFailedException pfe) {
                throw pfe;
            }
            throw new PublishFailedException("Publisher confirm failed for " + correlation.getId(), e);
        }
    }

    public static class PublishFailedException extends RuntimeException {
        public PublishFailedException(String message) {
            super(message);
        }

        public PublishFailedException(String message, Throwable cause) {
            super(message, cause);
            log.debug("publish failed", cause);
        }
    }
}

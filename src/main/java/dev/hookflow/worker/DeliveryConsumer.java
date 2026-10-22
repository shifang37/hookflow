package dev.hookflow.worker;

import com.rabbitmq.client.Channel;
import dev.hookflow.messaging.DeliveryMessage;
import dev.hookflow.messaging.RabbitTopology;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Consumes delivery work items and acknowledges them only after the outcome is
 * recorded in MySQL.
 *
 * <p>Redelivery policy: an unexpected exception causes {@code basicNack} without
 * requeue. That is safe — and deliberate — because the delivery row is the source
 * of truth and {@link DeliveryRecovery} re-enqueues anything that is stuck. A
 * requeue loop would just burn CPU on a message this worker cannot handle.
 */
@Component
@Profile({"worker", "all"})
public class DeliveryConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeliveryConsumer.class);

    private final DeliveryProcessor processor;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public DeliveryConsumer(DeliveryProcessor processor, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.processor = processor;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @RabbitListener(queues = RabbitTopology.DELIVERY_QUEUE, ackMode = "MANUAL")
    public void onMessage(String body,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        DeliveryMessage message;
        try {
            message = objectMapper.readValue(body, DeliveryMessage.class);
        } catch (RuntimeException e) {
            // Poison message: it can never be processed, so do not requeue it.
            log.error("Unparseable delivery message, routing to DLQ: {}", body, e);
            meterRegistry.counter("hookflow.consumer.poison").increment();
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        try {
            processor.process(message);
            channel.basicAck(deliveryTag, false);
            meterRegistry.counter("hookflow.consumer.acked").increment();
        } catch (Exception e) {
            log.error("Delivery {} attempt {} failed unexpectedly; it will be recovered from MySQL",
                    message.deliveryId(), message.attemptNo(), e);
            meterRegistry.counter("hookflow.consumer.nacked").increment();
            channel.basicNack(deliveryTag, false, false);
        }
    }
}

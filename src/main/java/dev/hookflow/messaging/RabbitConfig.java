package dev.hookflow.messaging;

import dev.hookflow.config.HookFlowProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Topology is declared only by the roles that use it, so an api-only deployment
 * does not need broker privileges at all.
 *
 * <h2>Queue length is part of the backpressure design</h2>
 *
 * <p>The delivery queue is declared with a length cap and
 * {@code x-overflow=reject-publish}. Overflow therefore fails the publish, which
 * the relay turns into "row goes back to NEW". That is the whole trick: the
 * backlog that cannot be worked off stays in MySQL, where it is queryable and
 * schedulable, instead of in the broker or in the worker's heap.
 *
 * <p>{@code drop-head} would be the wrong choice here — it silently discards work
 * that is already durable, and the delivery row would only recover after a
 * stuck-scan interval.
 */
@Configuration
@Profile({"relay", "worker", "all"})
public class RabbitConfig {

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(RabbitTopology.DLX, true, false);
    }

    @Bean
    public Queue deliveryQueue(HookFlowProperties properties) {
        QueueBuilder builder = QueueBuilder.durable(RabbitTopology.DELIVERY_QUEUE)
                .quorum()
                .deadLetterExchange(RabbitTopology.DLX)
                .deadLetterRoutingKey(RabbitTopology.DLQ_ROUTING_KEY);
        int maxLength = properties.relay().maxQueueLength();
        if (maxLength > 0) {
            builder = builder.maxLength(maxLength).overflow(QueueBuilder.Overflow.rejectPublish);
        }
        return builder.build();
    }

    @Bean
    public Queue deliveryDeadLetterQueue() {
        return QueueBuilder.durable(RabbitTopology.DELIVERY_DLQ).quorum().build();
    }

    @Bean
    public Binding deliveryDeadLetterBinding() {
        return BindingBuilder.bind(deliveryDeadLetterQueue())
                .to(deadLetterExchange())
                .with(RabbitTopology.DLQ_ROUTING_KEY);
    }
}

package dev.hookflow.messaging;

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
 */
@Configuration
@Profile({"relay", "worker", "all"})
public class RabbitConfig {

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(RabbitTopology.DLX, true, false);
    }

    @Bean
    public Queue deliveryQueue() {
        return QueueBuilder.durable(RabbitTopology.DELIVERY_QUEUE)
                .quorum()
                .deadLetterExchange(RabbitTopology.DLX)
                .deadLetterRoutingKey(RabbitTopology.DLQ_ROUTING_KEY)
                .build();
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

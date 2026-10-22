package dev.hookflow.messaging;

/**
 * Queue names in one place so the relay, the worker and the tests cannot drift.
 *
 * <p>Queues are quorum queues: replicated, durable, and at-least-once on broker
 * restart. The dead letter queue exists only for messages this service cannot
 * even parse — ordinary delivery failures are retried through MySQL, not through
 * the broker, so broker state never becomes the retry scheduler.
 */
public final class RabbitTopology {

    public static final String DELIVERY_QUEUE = "hookflow.delivery";
    public static final String DELIVERY_DLQ = "hookflow.delivery.dlq";
    public static final String DLX = "hookflow.dlx";
    public static final String DLQ_ROUTING_KEY = "hookflow.delivery.dead";

    private RabbitTopology() {
    }
}

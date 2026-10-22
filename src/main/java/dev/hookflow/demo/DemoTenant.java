package dev.hookflow.demo;

/**
 * One tenant of the demo, with everything the console needs to act as it.
 *
 * @param slug       path segment of the fake customer URL ({@code /hook/acme}); it is
 *                   how the receiver finds the right signing secret
 * @param apiKey     a real API key: the simulated business system calls the public
 *                   API with it, exactly like a customer would
 * @param maxInFlight the tenant's concurrency budget, changeable from the console
 */
public record DemoTenant(
        long tenantId,
        String name,
        String slug,
        String apiKey,
        long endpointId,
        String subscribedTypes,
        int maxInFlight,
        int weight) {
}

package dev.hookflow.demo;

import dev.hookflow.common.error.ApiException;
import dev.hookflow.crypto.SecretCipher;
import dev.hookflow.domain.endpoint.Endpoint;
import dev.hookflow.domain.fairness.TenantBudget;
import dev.hookflow.persistence.EndpointMapper;
import dev.hookflow.persistence.TenantMapper;
import dev.hookflow.service.ApiKeyService;
import dev.hookflow.service.EndpointService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Provisions and remembers the demo's tenants.
 *
 * <p>Two tenants on purpose, because a single tenant cannot show what this platform
 * is actually about:
 *
 * <ul>
 *   <li>{@code acme} — subscribes to everything, the tenant you flood.</li>
 *   <li>{@code quick} — subscribes to {@code order.paid} only, and only ever sends
 *       one event at a time. It is the tenant that gets starved when {@code acme}
 *       is allowed to own the queue, and the one that recovers instantly when
 *       {@code acme} is given a concurrency budget.</li>
 * </ul>
 *
 * <p>Provisioning is idempotent: tenants and endpoints are looked up first, so
 * restarting the app does not pile up demo objects. On a restart the endpoint's
 * signing secret is decrypted again — the platform has to be able to do that to sign
 * anything, and re-registering it is what lets the fake receiver keep its secrets in
 * memory only.
 */
@Component
@Profile("all")
@ConditionalOnProperty(prefix = "hookflow.demo", name = "enabled", havingValue = "true")
public class DemoRegistry {

    private static final Logger log = LoggerFactory.getLogger(DemoRegistry.class);

    private static final String SCOPE = "event:write,endpoint:write,delivery:read";

    /**
     * The two simulated customers.
     *
     * <p>{@code maxAttempts = 5} with the demo profile's 1s base delay puts a dead
     * letter roughly 15 seconds away, which is short enough to watch happen.
     */
    private static final List<Spec> SPECS = List.of(
            new Spec("acme", "demo-acme", "*", 32, 1, 5),
            new Spec("quick", "demo-quick", "order.paid", 32, 1, 5));

    private record Spec(String slug, String name, String subscribedTypes,
                        int maxInFlight, int weight, int maxAttempts) {
    }

    private final TenantMapper tenantMapper;
    private final EndpointMapper endpointMapper;
    private final ApiKeyService apiKeyService;
    private final EndpointService endpointService;
    private final SecretCipher secretCipher;
    private final DemoReceiver receiver;

    private final List<DemoTenant> tenants = new ArrayList<>();

    public DemoRegistry(TenantMapper tenantMapper,
                        EndpointMapper endpointMapper,
                        ApiKeyService apiKeyService,
                        EndpointService endpointService,
                        SecretCipher secretCipher,
                        DemoReceiver receiver) {
        this.tenantMapper = tenantMapper;
        this.endpointMapper = endpointMapper;
        this.apiKeyService = apiKeyService;
        this.endpointService = endpointService;
        this.secretCipher = secretCipher;
        this.receiver = receiver;
    }

    @PostConstruct
    void provision() {
        for (Spec spec : SPECS) {
            tenants.add(provisionOne(spec));
        }
        log.info("Demo tenants ready: {}", tenants.stream().map(DemoTenant::name).toList());
    }

    private DemoTenant provisionOne(Spec spec) {
        long tenantId = tenantMapper.findByName(spec.name())
                .map(t -> t.id())
                .orElseGet(() -> {
                    tenantMapper.insert(spec.name(), 0);
                    return tenantMapper.findByName(spec.name()).orElseThrow().id();
                });
        tenantMapper.updateBudget(tenantId, spec.maxInFlight(), spec.weight());

        // A fresh key per start: old ones stay valid, but the console always shows a
        // key that works even if the database was reset underneath a running app.
        ApiKeyService.IssuedKey key = apiKeyService.issue(tenantId, SCOPE);

        String url = "http://127.0.0.1:" + receiver.port() + "/hook/" + spec.slug();
        Endpoint endpoint = endpointMapper.findByTenantAndUrl(tenantId, url).orElse(null);
        byte[] secret;
        if (endpoint == null) {
            EndpointService.CreatedEndpoint created = endpointService.create(tenantId, url,
                    spec.subscribedTypes(), spec.maxAttempts(), 0);
            endpoint = created.endpoint();
            secret = Base64.getUrlDecoder().decode(created.signingSecret());
        } else {
            secret = secretCipher.decrypt(endpoint.secretCipher());
        }
        receiver.registerSecret(spec.slug(), secret);

        return new DemoTenant(tenantId, spec.name(), spec.slug(), key.token(), endpoint.id(),
                endpoint.subscribedTypes(), spec.maxInFlight(), spec.weight());
    }

    public List<DemoTenant> tenants() {
        return List.copyOf(tenants);
    }

    public Optional<DemoTenant> byId(long tenantId) {
        return tenants.stream().filter(t -> t.tenantId() == tenantId).findFirst();
    }

    public DemoTenant requireById(long tenantId) {
        return byId(tenantId).orElseThrow(() -> ApiException.notFound("Demo tenant"));
    }

    public Optional<DemoTenant> bySlug(String slug) {
        return tenants.stream().filter(t -> t.slug().equals(slug)).findFirst();
    }

    /** Changes a tenant's concurrency budget and returns the updated view. */
    public DemoTenant updateBudget(long tenantId, int maxInFlight) {
        DemoTenant current = requireById(tenantId);
        tenantMapper.updateBudget(tenantId, maxInFlight, current.weight());
        DemoTenant updated = new DemoTenant(current.tenantId(), current.name(), current.slug(),
                current.apiKey(), current.endpointId(), current.subscribedTypes(),
                maxInFlight, current.weight());
        tenants.replaceAll(t -> t.tenantId() == tenantId ? updated : t);
        return updated;
    }

    /** Budgets as the scheduler sees them, for the console's fairness panel. */
    public List<TenantBudget> budgets() {
        return tenantMapper.listBudgets();
    }
}

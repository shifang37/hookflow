package dev.hookflow.fairness;

import dev.hookflow.config.HookFlowProperties;
import dev.hookflow.domain.fairness.EndpointCap;
import dev.hookflow.domain.fairness.InFlight;
import dev.hookflow.domain.fairness.TenantBudget;
import dev.hookflow.persistence.DeliveryMapper;
import dev.hookflow.persistence.EndpointMapper;
import dev.hookflow.persistence.TenantMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The admission decision, isolated from MySQL.
 *
 * <p>These are the rules the whole fairness claim rests on, so they are worth
 * testing without a container: "a tenant at its budget is refused", "a tenant
 * with no budget is never refused", and — the subtle one — "a publication that
 * the snapshot has not seen yet already consumes budget".
 */
class AdmissionControlTest {

    private static final long TENANT = 7L;
    private static final long OTHER_TENANT = 8L;
    private static final long ENDPOINT = 70L;

    private TenantMapper tenantMapper;
    private EndpointMapper endpointMapper;
    private DeliveryMapper deliveryMapper;
    private AdmissionControl admission;

    @BeforeEach
    void setUp() {
        tenantMapper = mock(TenantMapper.class);
        endpointMapper = mock(EndpointMapper.class);
        deliveryMapper = mock(DeliveryMapper.class);
        when(endpointMapper.listConcurrencyCaps()).thenReturn(List.of());
        when(deliveryMapper.countInFlightByEndpoint()).thenReturn(List.of());
        admission = new AdmissionControl(tenantMapper, endpointMapper, deliveryMapper, properties());
    }

    @Test
    @DisplayName("a tenant at its concurrency budget is refused")
    void tenantAtBudgetIsRefused() {
        givenTenants(new TenantBudget(TENANT, 2, 1));
        givenTenantInFlight(new InFlight(TENANT, 2L));
        admission.refresh();

        assertThat(admission.admit(TENANT, ENDPOINT)).isFalse();
        assertThat(admission.blockedTenants()).contains(TENANT);
    }

    @Test
    @DisplayName("a tenant below its budget is admitted")
    void tenantBelowBudgetIsAdmitted() {
        givenTenants(new TenantBudget(TENANT, 2, 1));
        givenTenantInFlight(new InFlight(TENANT, 1L));
        admission.refresh();

        assertThat(admission.admit(TENANT, ENDPOINT)).isTrue();
        assertThat(admission.blockedTenants()).isEmpty();
    }

    @Test
    @DisplayName("budget 0 means uncapped: an unknown or unlimited tenant is never the bottleneck")
    void zeroBudgetMeansUncapped() {
        givenTenants(new TenantBudget(TENANT, 0, 1));
        givenTenantInFlight(new InFlight(TENANT, 5_000L));
        admission.refresh();

        assertThat(admission.admit(TENANT, ENDPOINT)).isTrue();
        // A tenant that is not in the snapshot at all (just created) must also pass.
        assertThat(admission.admit(OTHER_TENANT, ENDPOINT)).isTrue();
        assertThat(admission.blockedTenants()).isEmpty();
    }

    @Test
    @DisplayName("an endpoint at its own cap blocks only that endpoint")
    void endpointCapBlocksOnlyThatEndpoint() {
        givenTenants(new TenantBudget(TENANT, 100, 1));
        givenTenantInFlight(new InFlight(TENANT, 1L));
        when(endpointMapper.listConcurrencyCaps()).thenReturn(List.of(new EndpointCap(ENDPOINT, 4)));
        when(deliveryMapper.countInFlightByEndpoint()).thenReturn(List.of(new InFlight(ENDPOINT, 4L)));
        admission.refresh();

        assertThat(admission.admit(TENANT, ENDPOINT))
                .as("the receiver declared it cannot take more than 4 parallel calls")
                .isFalse();
        assertThat(admission.admit(TENANT, 999L))
                .as("other endpoints of the same tenant are unaffected")
                .isTrue();
        assertThat(admission.blockedEndpoints()).containsOnly(ENDPOINT);
    }

    @Test
    @DisplayName("a publication the snapshot has not seen yet already consumes budget")
    void localPublicationsConsumeBudgetBeforeTheNextRefresh() {
        givenTenants(new TenantBudget(TENANT, 1, 1));
        givenTenantInFlight(new InFlight(TENANT, 0L));
        admission.refresh();

        assertThat(admission.admit(TENANT, ENDPOINT)).isTrue();
        admission.markPublished(TENANT, ENDPOINT);

        assertThat(admission.admit(TENANT, ENDPOINT))
                .as("without this the relay would publish a whole batch into a budget of one")
                .isFalse();
    }

    @Test
    @DisplayName("a refresh folds the local publications into the real counts and resets them")
    void refreshFoldsLocalState() {
        givenTenants(new TenantBudget(TENANT, 2, 1));
        givenTenantInFlight(new InFlight(TENANT, 0L));
        admission.refresh();
        admission.markPublished(TENANT, ENDPOINT);

        // The worker finished the delivery: MySQL now reports zero in flight again.
        admission.refresh();

        assertThat(admission.admit(TENANT, ENDPOINT)).isTrue();
    }

    @Test
    @DisplayName("the scheduling weight is read from the tenant row, defaulting to 1")
    void weightDefaultsToOne() {
        givenTenants(new TenantBudget(TENANT, 10, 5));
        admission.refresh();

        assertThat(admission.weightOf(TENANT)).isEqualTo(5);
        assertThat(admission.weightOf(OTHER_TENANT)).isEqualTo(1);
    }

    private void givenTenants(TenantBudget... budgets) {
        when(tenantMapper.listBudgets()).thenReturn(List.of(budgets));
    }

    private void givenTenantInFlight(InFlight... rows) {
        when(deliveryMapper.countInFlightByTenant()).thenReturn(List.of(rows));
    }

    private static HookFlowProperties properties() {
        return new HookFlowProperties(
                null, null,
                new HookFlowProperties.Relay(50, 10, 300, 60, 7, 20_000),
                null, null, null, null,
                new HookFlowProperties.Fairness(true, 1_000),
                new HookFlowProperties.Backpressure(100_000, 10_000, 5, 500),
                new HookFlowProperties.Worker("defer"),
                null,
                null);
    }
}

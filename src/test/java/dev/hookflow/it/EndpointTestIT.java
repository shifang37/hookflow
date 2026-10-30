package dev.hookflow.it;

import dev.hookflow.persistence.TenantMapper;
import dev.hookflow.service.EndpointService;
import dev.hookflow.service.EndpointTestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The probe must be a real HTTP call to the endpoint's URL, and it must be invisible to the
 * event/delivery tables. The second half is the one an operator would notice only after the
 * fact: a "test" that fans out is not a test, it is a production event with a misleading name.
 */
class EndpointTestIT extends AbstractIntegrationTest {

    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private EndpointService endpointService;
    @Autowired
    private EndpointTestService endpointTestService;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("probes the receiver without writing an event, delivery or attempt")
    void probeIsNotAnIngest() {
        String name = "endpoint-test-it-" + UUID.randomUUID();
        tenantMapper.insert(name, 0);
        long tenantId = tenantMapper.findByName(name).orElseThrow().id();
        var created = endpointService.create(tenantId,
                "http://127.0.0.1:" + SharedReceiver.port() + "/hook", "*", 8);

        EndpointTestService.TestOutcome outcome = endpointTestService.test(
                tenantId, created.endpoint().id(), Map.of("orderId", 123));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.statusCode()).isEqualTo(200);
        assertThat(outcome.retryable()).isFalse();
        assertThat(outcome.responsePreview()).contains("\"ok\":true");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM event WHERE tenant_id = ?", Integer.class, tenantId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery WHERE tenant_id = ?", Integer.class, tenantId)).isZero();
    }
}

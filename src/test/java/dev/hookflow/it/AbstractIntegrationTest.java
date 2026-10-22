package dev.hookflow.it;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * Boots the real application against real MySQL and RabbitMQ.
 *
 * <p>No H2, no in-memory broker: every SQL construct this project relies on —
 * {@code FOR UPDATE SKIP LOCKED}, {@code INSERT ... ON DUPLICATE} semantics,
 * JSON columns, CHECK constraints, quorum queues — behaves differently in a
 * substitute. Testing against the real thing is the only way the acceptance
 * criteria mean anything.
 *
 * <p>Containers are started once per JVM and shared by every IT class, so the
 * whole suite pays the startup cost once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"all", "test"})
public abstract class AbstractIntegrationTest {

    protected static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("hookflow")
                    .withUsername("hookflow")
                    .withPassword("hookflow")
                    .withCommand("--innodb-flush-log-at-trx-commit=1");

    protected static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer("rabbitmq:4-management");

    static {
        MYSQL.start();
        RABBITMQ.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            MYSQL.stop();
            RABBITMQ.stop();
        }));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);

        // Redis is not part of these assertions; keep the limiter out of the way.
        registry.add("hookflow.rate-limit.enabled", () -> false);
        registry.add("hookflow.retry.base-delay-ms", () -> 200);
        registry.add("hookflow.retry.scan-interval-ms", () -> 200);
        registry.add("hookflow.relay.interval-ms", () -> 100);
        // A tighter admission refresh keeps the fairness IT from having to wait a
        // full second before the scheduler sees a budget change.
        registry.add("hookflow.fairness.refresh-interval-ms", () -> 200);
        // The lease sweeper would race tests that manipulate leases by hand; the
        // tests assert the reclaim semantics explicitly instead.
        registry.add("hookflow.lease.sweep-interval-ms", () -> 3_600_000);
        // Short read timeout so a deliberately slow receiver is classified as
        // TIMEOUT quickly instead of holding the suite for 10 seconds.
        registry.add("hookflow.delivery.read-timeout-ms", () -> 1_200);
        registry.add("hookflow.delivery.connect-timeout-ms", () -> 1_000);
    }
}

package dev.hookflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * HookFlow — reliable multi-tenant webhook delivery platform.
 *
 * <p>A single code base runs in three roles selected by Spring profile:
 * {@code api} (accept events, serve queries), {@code relay} (outbox -> broker),
 * {@code worker} (broker -> customer endpoint). The default profile {@code all}
 * runs everything in one process for local demos.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class HookFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(HookFlowApplication.class, args);
    }
}

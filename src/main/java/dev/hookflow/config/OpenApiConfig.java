package dev.hookflow.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI hookFlowOpenApi() {
        return new OpenAPI().info(new Info()
                .title("HookFlow API")
                .description("""
                        Reliable multi-tenant webhook delivery.

                        Delivery semantics: **at-least-once**. A receiver may observe the same
                        event twice if this platform never saw the first response. Deduplicate on
                        `event_id` (stable across retries) and verify `X-HookFlow-Signature`.
                        """)
                .version("0.1.0")
                .license(new License().name("Apache-2.0")));
    }
}

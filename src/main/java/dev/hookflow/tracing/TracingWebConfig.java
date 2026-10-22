package dev.hookflow.tracing;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the trace-id echo for every MVC handler. */
@Configuration
public class TracingWebConfig implements WebMvcConfigurer {

    private final TraceIdInterceptor traceIdInterceptor;

    public TracingWebConfig(TraceIdInterceptor traceIdInterceptor) {
        this.traceIdInterceptor = traceIdInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(traceIdInterceptor).addPathPatterns("/**");
    }
}

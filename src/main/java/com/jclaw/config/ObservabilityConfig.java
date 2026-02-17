package com.jclaw.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enables @Observed annotation support for distributed tracing spans.
 * Tracing bridge (micrometer-tracing-bridge-otel) propagates trace context
 * through W3C Trace Context headers automatically.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public ObservedAspect observedAspect(ObservationRegistry registry) {
        return new ObservedAspect(registry);
    }
}

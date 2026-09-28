package dev.resumeplatform.resumeai.infra;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import io.micrometer.observation.ObservationPredicate;

@Configuration
public class ObservabilityConfig {
    @Bean
    public ObservationPredicate ignoreHealthChecks() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext request
                && "/health".equals(request.getCarrier().getRequestURI()));
    }
}

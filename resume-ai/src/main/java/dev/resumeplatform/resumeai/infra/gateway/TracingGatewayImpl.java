package dev.resumeplatform.resumeai.infra.gateway;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import dev.resumeplatform.resumeai.core.gateway.TracingGateway;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

@Component
public class TracingGatewayImpl implements TracingGateway {
    private final ObservationRegistry observations;

    public TracingGatewayImpl(ObservationRegistry observations) {
        this.observations = observations;
    }

    @Override
    public <T> T traceTurn(Supplier<T> turn) {
        return Observation.createNotStarted("resume.agent.turn", observations)
                .contextualName("Agente RAG Curriculos")
                .observe(turn);
    }
}

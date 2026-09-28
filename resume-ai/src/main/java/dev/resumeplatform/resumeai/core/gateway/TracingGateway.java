package dev.resumeplatform.resumeai.core.gateway;

import java.util.function.Supplier;

public interface TracingGateway {
    <T> T traceTurn(Supplier<T> turn);
}

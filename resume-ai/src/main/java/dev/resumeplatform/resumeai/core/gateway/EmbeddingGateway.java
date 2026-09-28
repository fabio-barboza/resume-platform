package dev.resumeplatform.resumeai.core.gateway;

import java.util.List;

public interface EmbeddingGateway {
    float[] embed(String text);

    List<float[]> embedAll(List<String> texts);
}

package dev.resumeplatform.resumeai.infra.gateway;

import java.util.List;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import dev.resumeplatform.resumeai.core.gateway.EmbeddingGateway;

@Component
public class EmbeddingGatewayImpl implements EmbeddingGateway {
    private final EmbeddingModel embeddingModel;

    public EmbeddingGatewayImpl(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public float[] embed(String text) {
        return embeddingModel.embed(text);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        return embeddingModel.embed(texts);
    }
}

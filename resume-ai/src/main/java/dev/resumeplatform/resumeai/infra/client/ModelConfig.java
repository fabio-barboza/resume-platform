package dev.resumeplatform.resumeai.infra.client;

import java.util.Map;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import dev.resumeplatform.resumeai.config.ResumeAiProperties;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class ModelConfig {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean
    @Primary
    public OpenAiChatModel factualChatModel(ResumeAiProperties properties,
            ObjectProvider<ObservationRegistry> observationRegistry) {
        return chatModel(properties.main(), 0.0, observationRegistry);
    }

    @Bean
    public OpenAiChatModel workerChatModel(ResumeAiProperties properties,
            ObjectProvider<ObservationRegistry> observationRegistry) {
        return chatModel(properties.worker(), 0.0, observationRegistry);
    }

    @Bean
    public EmbeddingModel embeddingModel(ResumeAiProperties properties,
            ObjectProvider<ObservationRegistry> observationRegistry) {
        var embedding = properties.embedding();
        var options = OpenAiEmbeddingOptions.builder()
                .baseUrl(embedding.baseUrl())
                .apiKey(embedding.apiKey())
                .model(embedding.name())
                .build();
        return new OpenAiEmbeddingModel(MetadataMode.NONE, options,
                observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    private static OpenAiChatModel chatModel(ResumeAiProperties.Model role, double temperature,
            ObjectProvider<ObservationRegistry> observationRegistry) {
        var options = OpenAiChatOptions.builder()
                .baseUrl(role.baseUrl())
                .apiKey(role.apiKey())
                .model(role.name())
                .temperature(temperature)
                .streamUsage(true)
                .extraBody(parseExtraBody(role.extraBody()))
                .build();
        return OpenAiChatModel.builder()
                .options(options)
                .observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP))
                .build();
    }

    static Map<String, Object> parseExtraBody(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return JSON.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }
}

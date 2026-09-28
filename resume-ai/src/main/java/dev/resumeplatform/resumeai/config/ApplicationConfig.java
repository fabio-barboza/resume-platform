package dev.resumeplatform.resumeai.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.resumeplatform.resumeai.core.domain.settings.AgentSettings;
import dev.resumeplatform.resumeai.core.domain.settings.IngestionSettings;
import dev.resumeplatform.resumeai.entrypoint.agent.tools.ResumeTools;

@Configuration
public class ApplicationConfig {
    @Bean
    public IngestionSettings ingestionSettings(ResumeAiProperties properties) {
        return new IngestionSettings(properties.ingestion().extractionPages(), properties.ingestion().maxResumePages());
    }

    @Bean
    public AgentSettings agentSettings(ResumeAiProperties properties) {
        return new AgentSettings(properties.agent().maxToolCallsPerQuestion(), properties.agent().candidatesPerSearch(),
                properties.api().url());
    }

    /** As tools são entrypoint; o gateway do modelo só recebe os callbacks, sem depender de entrypoint. */
    @Bean
    public ToolCallbackProvider agentTools(ResumeTools resumeTools) {
        return MethodToolCallbackProvider.builder().toolObjects(resumeTools).build();
    }
}

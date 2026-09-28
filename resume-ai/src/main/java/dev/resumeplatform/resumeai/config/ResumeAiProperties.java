package dev.resumeplatform.resumeai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("resume")
public record ResumeAiProperties(
        Api api,
        Ingestion ingestion,
        Agent agent,
        Model main,
        Model worker,
        Embedding embedding,
        Storage storage) {
    public record Api(String host, int port) {
        public String url() {
            String visible = "0.0.0.0".equals(host) || "127.0.0.1".equals(host) ? "localhost" : host;
            return "http://" + visible + ":" + port;
        }

        public String swaggerUrl() {
            return url() + "/docs";
        }
    }

    public record Ingestion(int extractionPages, int maxResumePages) {
    }

    public record Agent(int maxToolCallsPerQuestion, int candidatesPerSearch) {
    }

    public record Model(String name, String baseUrl, String apiKey, String provider, String extraBody) {
    }

    public record Embedding(String name, String baseUrl, String apiKey, int dim) {
    }

    public record Storage(String accessKeyId, String secretAccessKey, String region, String bucket,
            String endpointUrl) {
    }
}

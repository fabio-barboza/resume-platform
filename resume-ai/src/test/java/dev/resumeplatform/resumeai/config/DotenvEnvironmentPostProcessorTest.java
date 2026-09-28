package dev.resumeplatform.resumeai.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotenvEnvironmentPostProcessorTest {
    @Test
    void readsValuesLikePythonDotenv(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(".env");
        Files.writeString(file, """
                # comentário
                MAIN_MODEL=qwen3.6:35B
                MAIN_MODEL_EXTRA_BODY={"chat_template_kwargs": {"enable_thinking": false}}
                LANGFUSE_SECRET_KEY="sk-lf-123"
                export API_PORT=8000
                S3_ENDPOINT_URL=http://localhost:9000 # MinIO local
                EMPTY=
                """);

        Map<String, Object> values = DotenvEnvironmentPostProcessor.readDotenvFile(file);

        assertThat(values).containsEntry("MAIN_MODEL", "qwen3.6:35B")
                .containsEntry("MAIN_MODEL_EXTRA_BODY", "{\"chat_template_kwargs\": {\"enable_thinking\": false}}")
                .containsEntry("LANGFUSE_SECRET_KEY", "sk-lf-123")
                .containsEntry("API_PORT", "8000")
                .containsEntry("S3_ENDPOINT_URL", "http://localhost:9000")
                .containsEntry("EMPTY", "");
    }

    @Test
    void missingFileIsEmpty(@TempDir Path dir) {
        assertThat(DotenvEnvironmentPostProcessor.readDotenvFile(dir.resolve("nada"))).isEmpty();
    }

    @Test
    void databaseUrlBecomesJdbc() {
        Map<String, Object> derived = new LinkedHashMap<>();
        DotenvEnvironmentPostProcessor.deriveDatasource("postgresql+psycopg://u:s3nh4@db:5433/base", derived);

        assertThat(derived).containsEntry("spring.datasource.url", "jdbc:postgresql://db:5433/base")
                .containsEntry("spring.datasource.username", "u")
                .containsEntry("spring.datasource.password", "s3nh4");
    }
}

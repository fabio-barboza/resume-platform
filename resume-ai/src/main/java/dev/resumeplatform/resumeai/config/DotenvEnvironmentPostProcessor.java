package dev.resumeplatform.resumeai.config;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.StringUtils;

public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    static final String DOTENV_SOURCE = "dotenv";
    static final String DERIVED_SOURCE = "resumeAiDerived";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path dotenv = Path.of(environment.getProperty("RESUME_AI_ENV_FILE", ".env"));
        Map<String, Object> values = readDotenvFile(dotenv);
        if (!values.isEmpty()) {
            var sources = environment.getPropertySources();
            if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
                sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new MapPropertySource(DOTENV_SOURCE, values));
            } else {
                sources.addLast(new MapPropertySource(DOTENV_SOURCE, values));
            }
        }

        Map<String, Object> derived = new LinkedHashMap<>();
        deriveDatasource(environment.getProperty("DATABASE_URL"), derived);
        deriveLangfuseAuthorization(environment, derived);
        if (!derived.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(DERIVED_SOURCE, derived));
        }
    }

    public static Map<String, Object> readDotenvFile(Path path) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) {
            return values;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return values;
        }
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).strip();
            values.put(key, unquote(line.substring(eq + 1).strip()));
        }
        return values;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            if ((first == '"' || first == '\'') && value.indexOf(first, 1) > 0) {
                return value.substring(1, value.indexOf(first, 1));
            }
        }

        int comment = value.indexOf(" #");
        return comment >= 0 ? value.substring(0, comment).strip() : value;
    }

    static void deriveDatasource(String databaseUrl, Map<String, Object> derived) {
        if (!StringUtils.hasText(databaseUrl)) {
            return;
        }

        String normalized = databaseUrl.replaceFirst("^postgresql\\+psycopg://", "postgresql://")
                .replaceFirst("^postgres://", "postgresql://");
        URI uri = URI.create(normalized);
        int port = uri.getPort() > 0 ? uri.getPort() : 5432;
        derived.put("spring.datasource.url", "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath());
        String userInfo = uri.getUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            derived.put("spring.datasource.username", colon >= 0 ? userInfo.substring(0, colon) : userInfo);
            if (colon >= 0) {
                derived.put("spring.datasource.password", userInfo.substring(colon + 1));
            }
        }
    }

    private static void deriveLangfuseAuthorization(ConfigurableEnvironment environment, Map<String, Object> derived) {
        String publicKey = environment.getProperty("LANGFUSE_PUBLIC_KEY", "");
        String secretKey = environment.getProperty("LANGFUSE_SECRET_KEY", "");
        String token = Base64.getEncoder()
                .encodeToString((publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
        derived.put("management.opentelemetry.tracing.export.otlp.headers.Authorization", "Basic " + token);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}

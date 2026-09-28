package dev.resumeplatform.resumeai.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import dev.resumeplatform.resumeai.infra.ResumeStorage;

@SpringBootTest
public abstract class DatabaseTest {
    @MockitoBean
    protected ResumeStorage storage;

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::url);
        registry.add("spring.datasource.username", TestDatabase::user);
        registry.add("spring.datasource.password", TestDatabase::password);

        registry.add("management.tracing.export.enabled", () -> "false");
    }
}

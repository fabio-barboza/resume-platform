package dev.resumeplatform.resumeai.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import dev.resumeplatform.resumeai.core.gateway.StorageGateway;

@SpringBootTest
public abstract class DatabaseTest {
    @MockitoBean
    protected StorageGateway storage;

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::url);
        registry.add("spring.datasource.username", TestDatabase::user);
        registry.add("spring.datasource.password", TestDatabase::password);

        registry.add("management.tracing.export.enabled", () -> "false");
    }
}

package dev.resumeplatform.resumeai.infra.gateway;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import dev.resumeplatform.resumeai.core.gateway.DatabaseHealthGateway;

@Component
public class DatabaseHealthGatewayImpl implements DatabaseHealthGateway {
    private final JdbcTemplate jdbc;

    public DatabaseHealthGatewayImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void check() {
        jdbc.queryForObject("SELECT 1", Integer.class);
    }
}

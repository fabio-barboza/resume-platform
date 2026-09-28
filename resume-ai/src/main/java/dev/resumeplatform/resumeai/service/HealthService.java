package dev.resumeplatform.resumeai.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HealthService {
    private final JdbcTemplate jdbc;

    public HealthService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void check() {
        jdbc.queryForObject("SELECT 1", Integer.class);
    }
}

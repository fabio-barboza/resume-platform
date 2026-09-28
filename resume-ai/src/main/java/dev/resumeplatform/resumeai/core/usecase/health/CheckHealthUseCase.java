package dev.resumeplatform.resumeai.core.usecase.health;

import org.springframework.stereotype.Service;

import dev.resumeplatform.resumeai.core.gateway.DatabaseHealthGateway;

@Service
public class CheckHealthUseCase {
    private final DatabaseHealthGateway database;

    public CheckHealthUseCase(DatabaseHealthGateway database) {
        this.database = database;
    }

    public void execute() {
        database.check();
    }
}

package dev.resumeplatform.resumeai.core.usecase.resume;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.resumeplatform.resumeai.core.domain.InventoryEntry;
import dev.resumeplatform.resumeai.core.gateway.ResumeGateway;

@Service
public class ListInventoryUseCase {
    private final ResumeGateway resumes;

    public ListInventoryUseCase(ResumeGateway resumes) {
        this.resumes = resumes;
    }

    @Transactional(readOnly = true)
    public List<InventoryEntry> execute() {
        return resumes.inventory();
    }
}

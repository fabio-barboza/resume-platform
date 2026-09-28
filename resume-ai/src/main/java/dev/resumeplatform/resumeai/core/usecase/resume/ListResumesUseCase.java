package dev.resumeplatform.resumeai.core.usecase.resume;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.resumeplatform.resumeai.core.domain.ResumePage;
import dev.resumeplatform.resumeai.core.gateway.ResumeGateway;

@Service
public class ListResumesUseCase {
    private final ResumeGateway resumes;

    public ListResumesUseCase(ResumeGateway resumes) {
        this.resumes = resumes;
    }

    @Transactional(readOnly = true)
    public ResumePage execute(int limit, int offset) {
        return new ResumePage(resumes.count(), resumes.list(limit, offset));
    }
}

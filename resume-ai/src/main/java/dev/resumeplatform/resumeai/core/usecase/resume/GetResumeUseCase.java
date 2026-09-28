package dev.resumeplatform.resumeai.core.usecase.resume;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.resumeplatform.resumeai.core.domain.Resume;
import dev.resumeplatform.resumeai.core.domain.exception.NotFoundException;
import dev.resumeplatform.resumeai.core.gateway.ResumeGateway;

@Service
public class GetResumeUseCase {
    private final ResumeGateway resumes;

    public GetResumeUseCase(ResumeGateway resumes) {
        this.resumes = resumes;
    }

    @Transactional(readOnly = true)
    public Resume execute(long documentId) {
        return resumes.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Documento " + documentId + " não encontrado."));
    }
}

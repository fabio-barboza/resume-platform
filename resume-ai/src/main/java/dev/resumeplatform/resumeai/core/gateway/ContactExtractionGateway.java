package dev.resumeplatform.resumeai.core.gateway;

import dev.resumeplatform.resumeai.core.domain.CandidateIdentity;

@FunctionalInterface
public interface ContactExtractionGateway {
    CandidateIdentity extract(String text);
}

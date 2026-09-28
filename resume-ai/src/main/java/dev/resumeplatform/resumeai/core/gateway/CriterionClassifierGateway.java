package dev.resumeplatform.resumeai.core.gateway;

import dev.resumeplatform.resumeai.core.domain.CriterionTriage;

@FunctionalInterface
public interface CriterionClassifierGateway {
    CriterionTriage classify(String question);
}

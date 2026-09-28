package dev.resumeplatform.resumeai.guardrail;

@FunctionalInterface
public interface CriterionClassifier {
    CriterionTriage classify(String question);
}

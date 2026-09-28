package dev.resumeplatform.resumeai.core.domain;

import java.util.List;

public record CriterionTriage(boolean protectedCriterion, List<String> attributes, String alternative) {
    public CriterionTriage {
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
    }

    public static CriterionTriage allowed() {
        return new CriterionTriage(false, List.of(), null);
    }
}

package dev.resumeplatform.resumeai.guardrail;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record CriterionTriage(
        @JsonPropertyDescription("true se a pergunta filtra candidatos por atributo protegido.")
        boolean protectedCriterion,
        @JsonPropertyDescription("Todos os atributos protegidos citados na pergunta, em português.")
        List<String> attributes,
        @JsonPropertyDescription("Reformulação da pergunta baseada em competência, ou null.")
        String alternative) {
    public CriterionTriage {
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
    }

    public static CriterionTriage allowed() {
        return new CriterionTriage(false, List.of(), null);
    }
}

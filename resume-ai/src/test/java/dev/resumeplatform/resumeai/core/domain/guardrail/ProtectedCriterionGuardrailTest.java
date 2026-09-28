package dev.resumeplatform.resumeai.core.domain.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.resumeplatform.resumeai.core.domain.CriterionTriage;

class ProtectedCriterionGuardrailTest {
    private final List<String> calls = new ArrayList<>();

    private ProtectedCriterionGuardrail guardrail(CriterionTriage verdict) {
        return new ProtectedCriterionGuardrail(question -> {
            calls.add(question);
            return verdict;
        });
    }

    @Test
    void protectedCriterionQuestionEndsTurnBeforeSearching() {
        var result = guardrail(new CriterionTriage(true, List.of("gênero", "idade"),
                "Quem tem experiência com liderança técnica?"))
                .check("Me traga só candidatos homens para essa vaga.");

        assertThat(result).isPresent();

        assertThat(result.get()).contains("gênero nem idade");

        assertThat(result.get()).contains("liderança técnica");
    }

    @Test
    void refusalWithoutAlternativeAsksToRephrase() {
        var result = guardrail(new CriterionTriage(true, List.of("idade"), null)).check("Quem tem menos de 30 anos?");

        assertThat(result.get()).startsWith("Não filtro candidatos por idade.").contains("Reformule por competência");
    }

    @Test
    void legitimateQuestionPasses() {
        assertThat(guardrail(CriterionTriage.allowed()).check("O Gustavo é sênior demais. Tem alguém em nível pleno?"))
                .isEmpty();
    }

    @Test
    void unavailableClassifierLetsQuestionThrough() {
        var failing = new ProtectedCriterionGuardrail(question -> {
            throw new IllegalStateException("modelo fora do ar");
        });

        assertThat(failing.check("Quem tem experiência com Kubernetes?")).isEmpty();
    }

    @Test
    void blankQuestionDoesNotCallClassifier() {
        assertThat(guardrail(new CriterionTriage(true, List.of("idade"), null)).check("   ")).isEmpty();
        assertThat(calls).isEmpty();
    }
}

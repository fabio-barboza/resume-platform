package dev.resumeplatform.resumeai.core.guardrail;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.resumeplatform.resumeai.core.domain.CriterionTriage;
import dev.resumeplatform.resumeai.core.gateway.CriterionClassifierGateway;

@Service
public class ProtectedCriterionGuardrail {
    private static final Logger log = LoggerFactory.getLogger(ProtectedCriterionGuardrail.class);

    private final CriterionClassifierGateway classifier;

    public ProtectedCriterionGuardrail(CriterionClassifierGateway classifier) {
        this.classifier = classifier;
    }

    public Optional<String> check(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }

        CriterionTriage verdict;
        try {
            verdict = classifier.classify(question.strip());
        } catch (RuntimeException ex) {
            log.warn("Guardrail de critério protegido não pôde classificar a pergunta; seguindo sem barrar.", ex);
            return Optional.empty();
        }

        if (verdict == null || !verdict.protectedCriterion()) {
            return Optional.empty();
        }
        log.info("Pergunta barrada pelo guardrail de critério protegido (atributos={}).", verdict.attributes());
        return Optional.of(refusal(verdict));
    }

    static String refusal(CriterionTriage verdict) {
        String attributes;
        List<String> listed = verdict.attributes();
        if (listed.isEmpty()) {
            attributes = "atributo pessoal protegido";
        } else if (listed.size() == 1) {
            attributes = listed.getFirst();
        } else {
            attributes = String.join(", ", listed.subList(0, listed.size() - 1)) + " nem " + listed.getLast();
        }

        List<String> lines = new ArrayList<>();
        lines.add("Não filtro candidatos por " + attributes + ". Critério protegido: usá-lo "
                + "para triagem é discriminação na contratação, e o dado estar no "
                + "currículo não autoriza selecionar por ele.");
        if (verdict.alternative() != null && !verdict.alternative().isBlank()) {
            lines.add("O que dá para responder é o equivalente por competência: *"
                    + verdict.alternative() + "* — quer que eu busque assim?");
        } else {
            lines.add("Reformule por competência — tecnologia, tempo de experiência, "
                    + "senioridade, formação, certificação — e eu busco.");
        }
        return String.join("\n\n", lines);
    }
}

package dev.resumeplatform.resumeai.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;

import dev.resumeplatform.resumeai.core.domain.chat.AgentListener;
import dev.resumeplatform.resumeai.core.domain.chat.TurnResult;
import dev.resumeplatform.resumeai.core.domain.guardrail.PersonMentions;
import dev.resumeplatform.resumeai.core.domain.text.TextFolding;
import dev.resumeplatform.resumeai.core.gateway.ResumeGateway;
import dev.resumeplatform.resumeai.core.usecase.chat.AskAgentUseCase;
import dev.resumeplatform.resumeai.core.usecase.resume.IngestResumeUseCase;
import dev.resumeplatform.resumeai.support.DatabaseTest;
import dev.resumeplatform.resumeai.support.PdfFixtures;

@Tag("eval")
public abstract class EvalTest extends DatabaseTest {
    private static boolean populated;

    @Autowired
    protected IngestResumeUseCase ingestResume;
    @Autowired
    protected ResumeGateway resumes;
    @Autowired
    protected AskAgentUseCase askAgent;

    @BeforeEach
    void populatedDatabase() {
        synchronized (EvalTest.class) {
            if (populated) {
                return;
            }
            List<Path> pdfs = PdfFixtures.samples();
            assumeFalse(pdfs.isEmpty(), "nenhum currículo de exemplo em " + PdfFixtures.SAMPLES_DIR);
            for (Path pdf : pdfs) {
                try {
                    ingestResume.execute(pdf.getFileName().toString(), Files.readAllBytes(pdf));
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
            assertThat(resumes.inventory()).as("esperava %d currículos ingeridos", pdfs.size())
                    .hasSize(pdfs.size());
            populated = true;
        }
    }

    protected class Conversation {
        private final String sessionId = "eval-" + UUID.randomUUID();

        public TurnResult ask(String question) {
            return askAgent.execute(sessionId, question, AgentListener.NONE);
        }
    }

    protected TurnResult ask(String question) {
        return new Conversation().ask(question);
    }

    protected List<Set<String>> realNameTokens() {
        return resumes.inventory().stream()
                .filter(r -> r.name() != null)
                .map(r -> fold(r.name()))
                .toList();
    }

    protected static Set<String> fold(String text) {
        Set<String> words = new HashSet<>();
        for (String word : text.split("\\s+")) {
            String bare = word.replaceAll("^[*_`(\\[]+|[*_`),.:;\\]]+$", "");
            if (!bare.isEmpty()) {
                words.add(TextFolding.fold(bare));
            }
        }
        return words;
    }

    protected List<String> inventedNames(String answer) {
        List<Set<String>> real = realNameTokens();
        return PersonMentions.find(answer).stream()
                .filter(mention -> real.stream().noneMatch(name -> {
                    Set<String> common = new HashSet<>(fold(mention));
                    common.retainAll(name);
                    return common.size() >= 2;
                }))
                .toList();
    }

    protected static String diagnosis(TurnResult turn, String context) {
        String answer = turn.content();
        return context + "\n  ferramentas no turno: " + (turn.toolNames().isEmpty() ? "nenhuma" : turn.toolNames())
                + "\n  resposta: " + answer.substring(0, Math.min(2000, answer.length()));
    }
}

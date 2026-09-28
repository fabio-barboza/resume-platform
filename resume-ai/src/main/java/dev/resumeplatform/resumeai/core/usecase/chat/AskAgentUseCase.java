package dev.resumeplatform.resumeai.core.usecase.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import dev.resumeplatform.resumeai.core.domain.chat.AgentListener;
import dev.resumeplatform.resumeai.core.domain.chat.ChatMessage;
import dev.resumeplatform.resumeai.core.domain.chat.ToolCall;
import dev.resumeplatform.resumeai.core.domain.chat.ToolResult;
import dev.resumeplatform.resumeai.core.domain.chat.TurnResult;
import dev.resumeplatform.resumeai.core.domain.guardrail.GroundingGuardrail;
import dev.resumeplatform.resumeai.core.domain.guardrail.ProtectedCriterionGuardrail;
import dev.resumeplatform.resumeai.core.domain.settings.AgentSettings;
import dev.resumeplatform.resumeai.core.gateway.ChatHistoryGateway;
import dev.resumeplatform.resumeai.core.gateway.ChatModelGateway;
import dev.resumeplatform.resumeai.core.gateway.TracingGateway;

/**
 * O agente: carrega o histórico da sessão, roda o turno (critério protegido → modelo → ferramentas com teto de
 * buscas → grounding) e grava o turno. O modelo roda fora de transação, e o turno só é gravado quando termina:
 * pergunta sem resposta nunca chega ao banco.
 */
@Service
public class AskAgentUseCase {
    private static final Logger log = LoggerFactory.getLogger(AskAgentUseCase.class);

    private static final int MAX_MODEL_CALLS = 25;

    private final ChatModelGateway model;
    private final ChatHistoryGateway history;
    private final TracingGateway tracing;
    private final ProtectedCriterionGuardrail protectedCriterion;
    private final TransactionTemplate transaction;
    private final int maxToolCalls;

    public AskAgentUseCase(ChatModelGateway model, ChatHistoryGateway history, TracingGateway tracing,
            ProtectedCriterionGuardrail protectedCriterion, TransactionTemplate transaction, AgentSettings settings) {
        this.model = model;
        this.history = history;
        this.tracing = tracing;
        this.protectedCriterion = protectedCriterion;
        this.transaction = transaction;
        this.maxToolCalls = settings.maxToolCallsPerQuestion();
    }

    public TurnResult execute(String sessionId, String message, AgentListener listener) {
        List<ChatMessage> previous = transaction.execute(tx -> history.load(sessionId));
        TurnResult result = tracing.traceTurn(() -> runTurn(previous, message, listener));
        transaction.executeWithoutResult(tx -> history.append(sessionId, result.messages()));
        return result;
    }

    private TurnResult runTurn(List<ChatMessage> previous, String question, AgentListener listener) {
        List<ChatMessage> turn = new ArrayList<>();
        turn.add(new ChatMessage.User(question));

        Optional<String> refusal = protectedCriterion.check(question);
        if (refusal.isPresent()) {
            turn.add(new ChatMessage.Assistant(refusal.get()));
            return new TurnResult(refusal.get(), turn);
        }

        int executedToolCalls = 0;
        for (int modelCalls = 0; modelCalls < MAX_MODEL_CALLS; modelCalls++) {
            List<ChatMessage> context = new ArrayList<>(previous.size() + turn.size());
            context.addAll(previous);
            context.addAll(turn);
            ChatMessage.Assistant answer = model.reply(context, listener::onToken);

            if (answer.hasToolCalls()) {
                turn.add(answer);
                answer.toolCalls().forEach(call -> listener.onToolStart(call.name()));
                List<ToolResult> results = new ArrayList<>();
                for (ToolCall call : answer.toolCalls()) {
                    String result;
                    if (executedToolCalls >= maxToolCalls) {
                        result = toolLimitMessage();
                    } else {
                        executedToolCalls++;
                        result = executeTool(call);
                    }
                    results.add(new ToolResult(call.id(), call.name(), result));
                    listener.onToolEnd(call.name());
                }
                turn.add(new ChatMessage.ToolResults(results));
                continue;
            }

            context.add(answer);
            switch (GroundingGuardrail.review(context)) {
                case GroundingGuardrail.Verdict.Pass pass -> {
                    turn.add(answer);
                    return new TurnResult(answer.text(), turn);
                }
                case GroundingGuardrail.Verdict.Repair repair -> {
                    turn.add(new ChatMessage.Assistant(repair.text()));
                    return new TurnResult(repair.text(), turn);
                }
                case GroundingGuardrail.Verdict.Retry retry -> {
                    turn.add(retry.correction());
                    listener.onGroundingRetry();
                }
                case GroundingGuardrail.Verdict.GiveUp giveUp -> {
                    turn.add(new ChatMessage.Assistant(giveUp.message()));
                    return new TurnResult(giveUp.message(), turn);
                }
            }
        }
        throw new IllegalStateException("O agente passou de " + MAX_MODEL_CALLS + " chamadas ao modelo no mesmo turno.");
    }

    private String executeTool(ToolCall call) {
        if (!model.toolNames().contains(call.name())) {
            return "Ferramenta desconhecida: " + call.name() + ". As disponíveis são: "
                    + String.join(", ", model.toolNames()) + ".";
        }
        String arguments = call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments();
        try {
            return model.callTool(call.name(), arguments);
        } catch (RuntimeException ex) {
            log.warn("Ferramenta {} falhou.", call.name(), ex);
            return "Erro ao executar " + call.name() + ": " + ex.getMessage() + ". Corrija os argumentos e tente de novo.";
        }
    }

    private String toolLimitMessage() {
        return "Limite de " + maxToolCalls + " buscas por pergunta atingido: esta chamada não foi executada. "
                + "Não chame mais ferramentas nesta pergunta — responda com o que já recuperou e diga "
                + "que a busca foi parcial.";
    }
}

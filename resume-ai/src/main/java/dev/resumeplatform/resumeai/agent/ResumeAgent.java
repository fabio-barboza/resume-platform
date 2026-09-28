package dev.resumeplatform.resumeai.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import dev.resumeplatform.resumeai.config.ResumeAiProperties;
import dev.resumeplatform.resumeai.guardrail.GroundingGuardrail;
import dev.resumeplatform.resumeai.guardrail.ProtectedCriterionGuardrail;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;

@Component
public class ResumeAgent {
    private static final Logger log = LoggerFactory.getLogger(ResumeAgent.class);

    private static final int MAX_MODEL_CALLS = 25;

    private final ChatModel chatModel;
    private final ProtectedCriterionGuardrail protectedCriterion;
    private final Map<String, ToolCallback> tools = new LinkedHashMap<>();
    private final int maxToolCalls;
    private final SystemMessage systemMessage;
    private final ObservationRegistry observations;

    public ResumeAgent(ChatModel chatModel, ResumeTools resumeTools, ProtectedCriterionGuardrail protectedCriterion,
            ResumeAiProperties properties, ObservationRegistry observations) {
        this.chatModel = chatModel;
        this.observations = observations;
        this.protectedCriterion = protectedCriterion;
        for (ToolCallback callback : ToolCallbacks.from(resumeTools)) {
            tools.put(callback.getToolDefinition().name(), callback);
        }
        this.maxToolCalls = properties.agent().maxToolCallsPerQuestion();

        this.systemMessage = new SystemMessage(SystemPrompt.indent(SystemPrompt.render("system_prompt.md",
                Map.of("max_tool_calls", maxToolCalls, "swagger_url", properties.api().swaggerUrl()))));
    }

    public String systemPrompt() {
        return systemMessage.getText();
    }

    public TurnResult run(List<Message> history, String question, AgentListener listener) {
        Observation observation = Observation.createNotStarted("resume.agent.turn", observations)
                .contextualName("Agente RAG Curriculos");
        return observation.observe(() -> runTurn(history, question, listener, observation));
    }

    private TurnResult runTurn(List<Message> history, String question, AgentListener listener,
            Observation turnObservation) {
        List<Message> turn = new ArrayList<>();
        turn.add(new UserMessage(question));

        Optional<String> refusal = protectedCriterion.check(question);
        if (refusal.isPresent()) {
            turn.add(new AssistantMessage(refusal.get()));
            return new TurnResult(refusal.get(), turn);
        }

        int executedToolCalls = 0;
        for (int modelCalls = 0; modelCalls < MAX_MODEL_CALLS; modelCalls++) {
            List<Message> context = new ArrayList<>(history.size() + turn.size());
            context.addAll(history);
            context.addAll(turn);
            AssistantMessage answer = callModel(context, listener, turnObservation);

            if (answer.hasToolCalls()) {
                turn.add(answer);
                answer.getToolCalls().forEach(call -> listener.onToolStart(call.name()));
                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                for (AssistantMessage.ToolCall call : answer.getToolCalls()) {
                    String result;
                    if (executedToolCalls >= maxToolCalls) {
                        result = toolLimitMessage();
                    } else {
                        executedToolCalls++;
                        result = executeTool(call);
                    }
                    responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
                    listener.onToolEnd(call.name());
                }
                turn.add(ToolResponseMessage.builder().responses(responses).build());
                continue;
            }

            context.add(answer);
            switch (GroundingGuardrail.review(context)) {
                case GroundingGuardrail.Verdict.Pass pass -> {
                    turn.add(answer);
                    return new TurnResult(answer.getText(), turn);
                }
                case GroundingGuardrail.Verdict.Repair repair -> {
                    turn.add(new AssistantMessage(repair.text()));
                    return new TurnResult(repair.text(), turn);
                }
                case GroundingGuardrail.Verdict.Retry retry -> {
                    turn.add(retry.correction());
                    listener.onGroundingRetry();
                }
                case GroundingGuardrail.Verdict.GiveUp giveUp -> {
                    turn.add(new AssistantMessage(giveUp.message()));
                    return new TurnResult(giveUp.message(), turn);
                }
            }
        }
        throw new IllegalStateException("O agente passou de " + MAX_MODEL_CALLS + " chamadas ao modelo no mesmo turno.");
    }

    private AssistantMessage callModel(List<Message> context, AgentListener listener, Observation turnObservation) {
        List<Message> messages = new ArrayList<>(context.size() + 1);
        messages.add(systemMessage);
        messages.addAll(context);
        Prompt prompt = new Prompt(messages, requestOptions());

        StringBuilder text = new StringBuilder();
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();

        var stream = chatModel.stream(prompt)
                .contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, turnObservation));
        for (ChatResponse response : stream.toIterable()) {
            if (response == null || response.getResult() == null) {
                continue;
            }
            AssistantMessage output = response.getResult().getOutput();
            String delta = output.getText();
            if (delta != null && !delta.isEmpty()) {
                text.append(delta);
                listener.onToken(delta);
            }
            toolCalls.addAll(output.getToolCalls());
        }
        return AssistantMessage.builder().content(text.toString()).toolCalls(toolCalls).build();
    }

    private ChatOptions requestOptions() {
        ChatOptions defaults = chatModel.getDefaultOptions();
        if (defaults instanceof ToolCallingChatOptions toolOptions) {
            return toolOptions.mutate().toolCallbacks(List.copyOf(tools.values())).build();
        }
        return ToolCallingChatOptions.builder().toolCallbacks(List.copyOf(tools.values())).build();
    }

    private String executeTool(AssistantMessage.ToolCall call) {
        ToolCallback callback = tools.get(call.name());
        if (callback == null) {
            return "Ferramenta desconhecida: " + call.name() + ". As disponíveis são: " + String.join(", ", tools.keySet())
                    + ".";
        }
        String arguments = call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments();
        try {
            return Observation.createNotStarted("resume.agent.tool", observations)
                    .contextualName(call.name())
                    .highCardinalityKeyValue("arguments", arguments)
                    .observe(() -> callback.call(arguments));
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

package dev.resumeplatform.resumeai.infra.gateway;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import dev.resumeplatform.resumeai.config.ResumeAiProperties;
import dev.resumeplatform.resumeai.core.domain.chat.ChatMessage;
import dev.resumeplatform.resumeai.core.domain.chat.ToolCall;
import dev.resumeplatform.resumeai.core.gateway.ChatModelGateway;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;

@Component
public class ChatModelGatewayImpl implements ChatModelGateway {
    private static final String SYSTEM_PROMPT_FILE = "prompts/system_prompt.md";
    private static final Pattern VARIABLE =
            Pattern.compile("\\$(?:(\\$)|([_a-z][_a-z0-9]*)|\\{([_a-z][_a-z0-9]*)})", Pattern.CASE_INSENSITIVE);
    private static final String INDENT = "    ";

    private final ChatModel chatModel;
    private final Map<String, ToolCallback> tools = new LinkedHashMap<>();
    private final SystemMessage systemMessage;
    private final ObservationRegistry observations;

    public ChatModelGatewayImpl(ChatModel chatModel, @Qualifier("agentTools") ToolCallbackProvider agentTools,
            ResumeAiProperties properties, ObservationRegistry observations) {
        this.chatModel = chatModel;
        this.observations = observations;
        for (ToolCallback callback : agentTools.getToolCallbacks()) {
            tools.put(callback.getToolDefinition().name(), callback);
        }
        this.systemMessage = new SystemMessage(indent(renderSystemPrompt(
                Map.of("max_tool_calls", properties.agent().maxToolCallsPerQuestion(),
                        "swagger_url", properties.api().swaggerUrl()))));
    }

    public String systemPrompt() {
        return systemMessage.getText();
    }

    @Override
    public ChatMessage.Assistant reply(List<ChatMessage> context, Consumer<String> onToken) {
        List<Message> messages = new ArrayList<>(context.size() + 1);
        messages.add(systemMessage);
        context.stream().map(SpringAiMessageMapper::toSpringAi).forEach(messages::add);
        Prompt prompt = new Prompt(messages, requestOptions());

        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();

        var stream = chatModel.stream(prompt);
        Observation turn = observations.getCurrentObservation();
        if (turn != null) {
            stream = stream.contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, turn));
        }
        for (ChatResponse response : stream.toIterable()) {
            if (response == null || response.getResult() == null) {
                continue;
            }
            AssistantMessage output = response.getResult().getOutput();
            String delta = output.getText();
            if (delta != null && !delta.isEmpty()) {
                text.append(delta);
                onToken.accept(delta);
            }
            output.getToolCalls().stream().map(SpringAiMessageMapper::toDomain).forEach(toolCalls::add);
        }
        return new ChatMessage.Assistant(text.toString(), toolCalls);
    }

    @Override
    public Set<String> toolNames() {
        return tools.keySet();
    }

    @Override
    public String callTool(String name, String arguments) {
        ToolCallback callback = tools.get(name);
        if (callback == null) {
            throw new IllegalArgumentException("Ferramenta desconhecida: " + name);
        }
        return Observation.createNotStarted("resume.agent.tool", observations)
                .contextualName(name)
                .highCardinalityKeyValue("arguments", arguments)
                .observe(() -> callback.call(arguments));
    }

    private ChatOptions requestOptions() {
        List<ToolCallback> callbacks = List.copyOf(tools.values());
        ChatOptions defaults = chatModel.getDefaultOptions();
        if (defaults instanceof ToolCallingChatOptions toolOptions) {
            return toolOptions.mutate().toolCallbacks(callbacks).build();
        }
        return ToolCallingChatOptions.builder().toolCallbacks(callbacks).build();
    }

    /** Mesma substituição do {@code string.Template} do Python: {@code $nome}, {@code ${nome}} e {@code $$}. */
    private static String renderSystemPrompt(Map<String, Object> variables) {
        String text;
        try {
            text = new ClassPathResource(SYSTEM_PROMPT_FILE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        Matcher matcher = VARIABLE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement;
            if (matcher.group(1) != null) {
                replacement = "$";
            } else {
                String name = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
                if (!variables.containsKey(name)) {
                    throw new IllegalArgumentException("Variável sem valor no prompt " + SYSTEM_PROMPT_FILE + ": $" + name);
                }
                replacement = String.valueOf(variables.get(name));
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** O agent.py reaplica 4 espaços em toda linha não vazia do prompt; sem eles o eval de gráfico quebra. */
    static String indent(String text) {
        StringBuilder out = new StringBuilder(text.length() + 256);
        int start = 0;
        while (start < text.length()) {
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline + 1;
            String line = text.substring(start, end);
            if (!line.isBlank()) {
                out.append(INDENT);
            }
            out.append(line);
            start = end;
        }
        return out.toString();
    }
}

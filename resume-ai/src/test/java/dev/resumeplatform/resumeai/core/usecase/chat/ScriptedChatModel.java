package dev.resumeplatform.resumeai.core.usecase.chat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import dev.resumeplatform.resumeai.core.domain.chat.ToolCall;
import reactor.core.publisher.Flux;

public class ScriptedChatModel implements ChatModel {
    private final Iterator<AssistantMessage> answers;
    public final List<List<Message>> prompts = new ArrayList<>();

    public ScriptedChatModel(AssistantMessage... answers) {
        this.answers = List.of(answers).iterator();
    }

    public static AssistantMessage toolCall(String id, String name, String arguments) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, arguments))).build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt.getInstructions());
        return new ChatResponse(List.of(new Generation(answers.next())));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        prompts.add(prompt.getInstructions());
        AssistantMessage answer = answers.next();
        if (answer.hasToolCalls()) {
            return Flux.just(new ChatResponse(List.of(new Generation(answer))));
        }
        String text = answer.getText();
        int half = text.length() / 2;
        return Flux.just(text.substring(0, half), text.substring(half))
                .map(delta -> new ChatResponse(List.of(new Generation(new AssistantMessage(delta)))));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ToolCallingChatOptions.builder().build();
    }
}

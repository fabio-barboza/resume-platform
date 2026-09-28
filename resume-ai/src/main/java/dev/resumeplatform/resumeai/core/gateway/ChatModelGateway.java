package dev.resumeplatform.resumeai.core.gateway;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import dev.resumeplatform.resumeai.core.domain.chat.ChatMessage;

public interface ChatModelGateway {
    /** Uma chamada ao modelo com o prompt de sistema e as ferramentas; repassa cada delta de texto a {@code onToken}. */
    ChatMessage.Assistant reply(List<ChatMessage> context, Consumer<String> onToken);

    Set<String> toolNames();

    String callTool(String name, String arguments);
}

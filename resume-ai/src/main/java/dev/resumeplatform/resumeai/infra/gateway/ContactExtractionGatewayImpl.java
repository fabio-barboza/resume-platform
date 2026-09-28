package dev.resumeplatform.resumeai.infra.gateway;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import dev.resumeplatform.resumeai.core.domain.CandidateIdentity;
import dev.resumeplatform.resumeai.core.gateway.ContactExtractionGateway;

@Component
public class ContactExtractionGatewayImpl implements ContactExtractionGateway {
    static final String PROMPT = """
            Extraia os dados de contato do candidato a partir do trecho de \
            currículo abaixo.

            Regras:
            - `name`: o nome completo do candidato dono do currículo. Não confunda com \
            nome de empresa, faculdade, curso, cliente ou referência profissional.
            - `email`: o email de contato do próprio candidato.
            - `phone`: o telefone de contato do próprio candidato.
            - Se um dado não estiver no texto, devolva null. Não invente e não deduza.

            Currículo:
            ---
            %s
            ---""";

    public record ContactExtractionResponse(
            @JsonPropertyDescription("Nome completo do candidato, ou null.") String name,
            @JsonPropertyDescription("Email de contato do candidato, ou null.") String email,
            @JsonPropertyDescription("Telefone de contato do candidato, ou null.") String phone) {
        CandidateIdentity toDomain() {
            return new CandidateIdentity(name, email, phone);
        }
    }

    private final ChatClient chatClient;

    public ContactExtractionGatewayImpl(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public CandidateIdentity extract(String text) {
        ContactExtractionResponse result = chatClient.prompt().user(PROMPT.formatted(text)).call()
                .entity(ContactExtractionResponse.class);
        return result == null ? null : result.toDomain();
    }
}

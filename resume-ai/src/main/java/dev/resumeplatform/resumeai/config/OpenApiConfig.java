package dev.resumeplatform.resumeai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI resumeAiOpenApi() {
        return new OpenAPI().info(new Info()
                .title("resume-ai API")
                .version("0.1.0")
                .description("""
                        Manutenção da base de currículos consultada pelo agente RAG.

                        O currículo enviado é a **fonte da verdade** do cadastro: a cada ingestão, \
                        nome, email e telefone do candidato são extraídos do arquivo e substituem por \
                        inteiro o que estava gravado — inclusive quando o valor extraído for nulo. \
                        O email é a chave natural que vincula currículos ao mesmo candidato.

                        Não há autenticação: qualquer chamada pode alterar qualquer currículo."""));
    }
}

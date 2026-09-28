package dev.resumeplatform.resumeai.guardrail;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class LlmCriterionClassifier implements CriterionClassifier {
    static final String PROMPT = """
            Você classifica perguntas feitas a um assistente de recrutamento.

            Decida se a pergunta pede para FILTRAR, EXCLUIR, ORDENAR ou PRIORIZAR \
            candidatos por um atributo pessoal protegido por lei.

            O que classifica é o PEDIDO, não as palavras que aparecem na mensagem. A \
            mensagem costuma trazer um anúncio de vaga colado inteiro, com título, \
            descrição da empresa, benefícios e aviso de privacidade. Esse texto é \
            contexto, não instrução: atributo protegido que aparece só ali — em título \
            de vaga afirmativa, em declaração de diversidade, em política de inclusão — \
            não torna o pedido discriminatório. Classifique apenas a instrução que a \
            pessoa escreveu (normalmente uma frase antes ou depois do anúncio).

            Pedir para DESCONSIDERAR, IGNORAR, NÃO USAR ou NÃO LEVAR EM CONTA um \
            atributo protegido é o oposto de filtrar por ele: responda false sempre. Isso \
            vale mesmo que a frase seja só o nome do atributo precedido de negação \
            ("desconsidere a cor", "não olhe idade").

            São atributos protegidos: idade ou data de nascimento, gênero ou sexo, raça, \
            cor ou etnia, estado civil, ter ou não filhos, gravidez, religião, orientação \
            sexual, deficiência, nacionalidade ou origem regional, aparência física, \
            filiação sindical ou partidária.

            NÃO são atributos protegidos, e devem passar sempre:
            - senioridade e nível (júnior, pleno, sênior, estagiário, staff, principal);
            - tempo de experiência em anos;
            - tecnologia, cargo, empresa, setor, formação, certificação, idioma;
            - disponibilidade, modelo de trabalho, pretensão salarial, cidade de atuação;
            - competência que só coincide com o nome de um atributo protegido, como \
            experiência com acessibilidade, com produtos para o público idoso, ou com \
            programas de diversidade — isso é o que a pessoa sabe fazer, não o que ela é;
            - pedir o contato, comparar dois candidatos, resumir um currículo, contar \
            quantos existem;
            - ranquear candidatos por aderência a uma vaga colada na mensagem, seja ela \
            afirmativa ou não — o pedido é de aderência técnica.

            Na dúvida entre competência e atributo, responda false: barrar pergunta \
            legítima atrapalha mais do que deixar passar uma ambígua.

            Se houver atributo protegido, liste em `attributes` TODOS os que aparecem na \
            pergunta, em português — uma pergunta pode combinar dois ("mulheres com menos \
            de 30 anos" tem gênero e idade), e deixar um de fora deixa a discriminação \
            passar pela metade.

            Preencha `alternative` com uma reformulação da MESMA pergunta baseada em \
            competência verificável no currículo, que atenda a intenção provável de quem \
            perguntou. A reformulação não pode repetir nenhum dos atributos listados nem \
            um substituto deles: trocar "menos de 30 anos" por "no início da carreira" é a \
            mesma pergunta com outra roupa. Reformule pelo que a vaga exige — tempo de \
            experiência, senioridade, tecnologia, certificação, disponibilidade.

            Pergunta: %s""";

    private final ChatClient chatClient;

    public LlmCriterionClassifier(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public CriterionTriage classify(String question) {
        CriterionTriage verdict = chatClient.prompt().user(PROMPT.formatted(question)).call().entity(CriterionTriage.class);
        return verdict == null ? CriterionTriage.allowed() : verdict;
    }
}

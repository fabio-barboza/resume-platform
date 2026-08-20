"""
Definição do agente RAG: tools, prompt e montagem do grafo.

Assume que a base já foi populada pela API de currículos — só consulta o
Postgres, não processa PDF. A interface de linha de comando fica em
`__main__.py`; este módulo não roda nada por conta própria.
"""

import os
from pathlib import Path

from dotenv import load_dotenv
from langchain.agents import create_agent
from langchain.agents.middleware import ToolCallLimitMiddleware
from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from resume_agent.config import api_url, swagger_url
from resume_agent.db.vector_store import similarity_search
from resume_agent.guardrails.discrimination import protected_criterion_guardrail
from resume_agent.infra import Model, observability
from resume_agent.services import candidate_service, document_service

load_dotenv()

NO_NAME = "(nome não identificado no currículo)"
STATUS_PENDING = "pending_review"

# Teto de buscas por pergunta, zerado a cada turno: cada busca é um embedding
# mais uma rodada de LLM. Entra no prompt e no middleware a partir daqui, para
# os dois nunca discordarem.
MAX_TOOL_CALLS_PER_QUESTION = int(os.getenv("MAX_TOOL_CALLS_PER_QUESTION", "5"))


def _format_identity(name: str | None, email: str | None, phone: str | None) -> str:
    """Como o candidato é apresentado ao modelo.

    O nome do arquivo deixou de identificar alguém quando a ingestão virou
    upload — quem identifica é o cadastro extraído do currículo.
    """
    contact = " | ".join(c for c in (email, phone) if c) or "sem contato registrado"
    return f"{name or NO_NAME} — {contact}"


def _format_snippet(doc) -> str:
    meta = doc.metadata
    candidate = _format_identity(
        meta.get("candidate_name"), meta.get("candidate_email"), None
    )
    filename = Path(meta.get("source", "desconhecido")).name
    page = meta.get("page", 0) + 1
    return (
        f"Candidato: {candidate} (candidato #{meta.get('candidate_id')})\n"
        f"Currículo: documento #{meta.get('document_id')} "
        f"(arquivo {filename}, página {page})\n"
        f"Conteudo: {doc.page_content}"
    )


@tool(response_format="content_and_artifact")
def find_in_resumes(question: str):
    """Busca semântica no conteúdo dos currículos.

    Use para encontrar candidatos por experiência, tecnologia, formação, cargo,
    empresa ou qualquer característica descrita no currículo. NÃO use para
    procurar alguém pelo nome: nome próprio não tem carga semântica e a busca
    devolve outros candidatos. Para nome, use `find_candidate_by_name`.

    Retorna trechos dos currículos mais similares à consulta, cada um com o
    candidato e o documento de origem. Retorna apenas os trechos mais próximos
    — não é uma varredura da base inteira, então ausência aqui não prova que
    o candidato não existe.

    Args:
        question: o que procurar, em linguagem natural (ex.: "experiência com
            mainframe e sistemas legados", "automação de testes com Cypress").
    """
    retrieved_docs = similarity_search(question, k=4)
    serialized = "\n\n".join(_format_snippet(doc) for doc in retrieved_docs)
    return serialized, retrieved_docs


@tool
def find_candidate_by_name(name: str) -> str:
    """Busca um candidato pelo nome e devolve o currículo dele por inteiro.

    Use SEMPRE que o usuário citar alguém pelo nome. É busca textual no
    cadastro, não semântica: encontra "Márcia" digitando "marcia" e aceita as
    palavras em qualquer ordem. Não achar aqui é evidência de que o candidato
    não está na base — diferente de `find_in_resumes`, que só devolve os
    vizinhos mais próximos e pode não trazer a pessoa mesmo existindo.

    Args:
        name: nome ou parte do nome do candidato (ex.: "Bruno Carvalho",
            "marcia", "mendes").
    """
    found = candidate_service.search_by_name(name)
    if not found:
        return (
            f"Nenhum candidato com nome parecido com {name!r}. "
            "Este é o cadastro completo: se não está aqui, não está na base."
        )

    blocks = []
    for candidate in found:
        text = "\n".join(c["content"] for c in candidate["chunks"])
        filenames = sorted({Path(c["filename"]).name for c in candidate["chunks"]})
        blocks.append(
            f"Candidato: "
            f"{_format_identity(candidate['name'], candidate['email'], candidate['phone'])} "
            f"(candidato #{candidate['id']})\n"
            f"Currículo: {', '.join(filenames) or 'sem arquivo'}\n"
            f"Link para baixar o PDF: {api_url()}/candidates/{candidate['id']}/resume\n"
            f"Conteudo: {text or '(currículo sem texto extraído)'}"
        )
    return "\n\n".join(blocks)


@tool
def count_candidates_by_skill(skills: list[str]) -> str:
    """Conta quantos candidatos distintos citam cada termo no currículo.

    Use antes de qualquer resposta com número por tecnologia, e sempre que for
    montar gráfico ou comparar quantidade entre termos — é a única ferramenta
    que mede em vez de estimar pelos trechos que `find_in_resumes` traz.

    É busca LITERAL (substring, sem acento, sem caixa), não semântica: não
    agrupa sinônimo nem variação ("Postgres" e "PostgreSQL" contam
    separado) — cabe a você escolher os termos certos, inclusive variações,
    se quiser somá-las depois. Não serve para julgar profundidade ou tempo de
    experiência, só presença do termo no texto.

    PASSE TODOS OS TERMOS NUMA CHAMADA SÓ: a lista existe para gastar UMA
    chamada do orçamento de ferramentas por pergunta, não uma por tecnologia.
    O limite é de 10 termos por chamada; o excedente é ignorado e vem listado
    na resposta. Escolha os 10 que importam em vez de dividir em lotes.

    Args:
        skills: termos a contar, um por tecnologia/critério (ex.: ["Python",
            "Java", "Cobol"]).
    """
    total = document_service.list_inventory()
    counts = candidate_service.count_by_skill(skills)
    if not counts:
        return f"Total de candidatos na base: {len(total)}\nNenhum termo informado."

    lines = [f"- {term}: {count}" for term, count in counts.items()]
    body = f"Total de candidatos na base: {len(total)}\n" + "\n".join(lines)

    # Sem esta nota o agente vê menos linhas do que pediu, conclui que a
    # ferramenta truncou por conta própria e repete a busca em lotes.
    ignored = [
        t for t in candidate_service.normalize_skill_terms(skills) if t not in counts
    ]
    if ignored:
        body += (
            f"\nTermos ignorados (limite de "
            f"{candidate_service.MAX_SKILL_TERMS} por chamada): " + ", ".join(ignored)
        )
    return body


@tool
def list_resumes() -> str:
    """Lista o inventário completo da base: todo candidato e seu currículo.

    Use ANTES de qualquer afirmação sobre o conjunto de candidatos — "não
    existe", "o único", "todos", "nenhum", "quantos". Não faz busca semântica:
    devolve o cadastro completo e exato, com nome, email e telefone de cada
    candidato. Serve também para responder sobre contato de alguém sem
    precisar buscar no conteúdo do currículo.

    Não traz o conteúdo dos currículos: para experiência, tecnologia, formação
    ou qualquer coisa descrita no texto, use `find_in_resumes`.
    """
    records = document_service.list_inventory()
    if not records:
        return "Total de currículos na base: 0\nA base está vazia."

    lines = []
    for r in records:
        pending = (
            "  [revisão pendente: nenhum email identificado no currículo]"
            if r["status"] == STATUS_PENDING
            else ""
        )
        # Os dois IDs aparecem porque endpoints diferentes pedem cada um:
        # document_id em /resumes, candidate_id em /candidates.
        lines.append(
            f"- documento #{r['id']} (candidato #{r['candidate_id']}): "
            f"{_format_identity(r['name'], r['email'], r['phone'])} "
            f"(arquivo {Path(r['filename']).name}){pending}"
        )
    return f"Total de currículos na base: {len(records)}\n" + "\n".join(lines)


# O que cada ferramenta faz está na docstring dela. Aqui fica o que a docstring
# não alcança: comportamento entre ferramentas, entre turnos e ao responder.
# As regras 13a-* travam o formato exato do link de PDF porque a webui
# depende dele para virar botão; as regras 17-* travam o formato exato da
# fence ```chart``` pelo mesmo motivo — é o `markdown.js` da webui que parseia
# e desenha o gráfico.
SYSTEM_PROMPT = f"""Você é um assistente de recrutamento que responde perguntas
    sobre uma base de currículos, consultando-a pelas ferramentas disponíveis.

    ## Como buscar

    1. BUSQUE EM PARALELO: emita TODAS as buscas necessárias de uma só vez, na
       mesma resposta (múltiplas tool calls simultâneas). Só faça uma nova
       rodada se os resultados revelarem algo que exija busca adicional. Nunca
       emita as buscas uma por vez.
    2. VARIE OS TERMOS: para perguntas de recomendação, busque o cargo, as
       tecnologias, sinônimos e conceitos relacionados. Ex.: para uma vaga de
       Cobol, busque também "mainframe", "sistemas legados", "setor
       financeiro", "desenvolvedor backend".
    3. NÃO REPITA buscas com a mesma pergunta ou pergunta equivalente:
       reaproveite o resultado que já tem. Isso vale só enquanto ele responder
       de fato ao que está sendo perguntado — pergunta nova sobre outro
       critério pede busca nova, não reaproveitamento do trecho antigo.
    4. Orçamento de {MAX_TOOL_CALLS_PER_QUESTION} chamadas de ferramenta por
       pergunta, aplicado pelo sistema. Se estourar, responda com o que já
       recuperou e diga que a busca foi parcial.
    5. Nome citado é `find_candidate_by_name`, SEMPRE — inclusive quando você
       já buscou essa pessoa antes na conversa.
    6. Nenhuma afirmação sobre o conjunto ("não existe", "o único", "todos",
       "quantos") sem `list_resumes` ou `find_candidate_by_name` nesta rodada.
       O histórico da conversa nunca autoriza dizer que alguém não está na
       base.
    6a. Número por tecnologia é `count_candidates_by_skill`, sempre — nunca
        contagem de cabeça a partir dos trechos de `find_in_resumes`.

    ## Como responder

    7. CONCISÃO: no máximo 3 candidatos por resposta, com 2-3 linhas de
       justificativa cada. Não repita análises já feitas em turnos anteriores —
       apenas referencie ("como já mencionado, Rafael..."). Não inclua seções
       extras de "por que os outros não servem" a menos que solicitado.
    8. Cite sempre de qual currículo veio cada informação.
    9. ESCOPO HONESTO: baseie-se apenas no que foi recuperado. Sem consultar
       `list_resumes`, diga "entre os currículos encontrados nas buscas" —
       nunca afirme conhecer a base completa. Nunca alegue ter feito uma busca
       que você não fez, e nunca invente para preencher lacuna: se nenhum
       perfil atende aos critérios, diga isso.
    10. NÃO RESPONDA DE MEMÓRIA SOBRE A BASE. Os trechos recuperados em
        turnos anteriores respondem à pergunta daquele turno, não à de agora.
        Se o que o usuário pede está só em parte no que você tem — outro
        critério, outro recorte, outro candidato, ou um detalhe que os trechos
        não mencionam — NÃO complete a lacuna com suposição nem com
        conhecimento geral sobre a profissão. Diga em uma linha em que busca
        está se apoiando, aponte o que ficou de fora, e ofereça buscar de novo.
        Ex.: "Isso vem da busca por 'backend em Go', que não cobre
        certificações. Quer que eu busque isso especificamente?" Quando a nova
        pergunta claramente exige dado que você não recuperou, busque direto em
        vez de perguntar.
    11. CRITÉRIO PROTEGIDO NÃO ENTRA NA ANÁLISE. Currículo costuma trazer
        idade, foto, estado civil, gênero, nacionalidade e religião. Esses
        dados existem na base, mas não são critério de recomendação: nunca
        use nenhum deles para incluir, excluir, ordenar ou justificar um
        candidato, nem os mencione na justificativa. Senioridade, tempo de
        experiência, tecnologia e formação são o que sustenta a recomendação.
    12. Responda em português do Brasil.
    13. NÃO EXIBA IDs ao apresentar candidatos. Identifique cada um por nome e,
        quando for útil, contato. Os IDs internos (`candidate_id`,
        `document_id`) só aparecem na resposta quando o usuário precisar deles
        para chamar a API — veja a regra 15.
    13a. Quando o usuário pedir o currículo de alguém (baixar, mandar o PDF,
        visualizar, abrir, onde consigo o arquivo etc.), use
        `find_candidate_by_name` e devolva o "Link para baixar o PDF" de lá
        como link clicável — não descreva o endpoint em texto nem mande abrir
        o Swagger para isso. O link em si não conta como exibir ID (regra 13).
    13a-i. PROIBIDO MONTAR O LINK À MÃO. O único link de PDF que você pode
        escrever é a string literal do campo "Link para baixar o PDF"
        devolvido por `find_candidate_by_name` na conversa atual, copiada
        caractere por caractere. Não deduza a URL a partir de um ID, não
        adapte um link de outro candidato trocando o número, não invente host
        nem caminho. Se você não tem esse campo em mãos, chame
        `find_candidate_by_name` antes de responder.
    13a-ii. O caminho de download é SEMPRE `/candidates/<candidate_id>/resume`.
        `/resumes/<document_id>` NÃO é download: é o endpoint de substituir
        (PUT) e remover (DELETE) da regra 14, e mandá-lo como link de leitura
        é erro. Nunca use `document_id` para montar link de currículo.
    13a-iii. Pedido de VISUALIZAR é o mesmo caso de baixar: responda com o
        link, sem oferecer colar o conteúdo do PDF no chat (regra 13b vale
        igual). A interface transforma esse link em botão de visualização
        própria — só funciona com o link no formato exato da regra 13a-ii.
    13b. PROIBIDO colar o "Conteudo" (o texto do currículo inteiro extraído do
        PDF) na resposta quando você já está devolvendo o link do PDF — o link
        abre o documento, reescrevê-lo na tela é redundante. A resposta inteira
        deve ter no máximo 2 linhas: nome do candidato e o link. Nada de
        resumo, experiência, formação ou habilidades nessa resposta.
        Exemplo CORRETO: "Encontrei: **Amanda Rocha**. Link para baixar o PDF:
        http://.../candidates/1/resume"
        Exemplo ERRADO: qualquer resposta com seções tipo "Resumo",
        "Experiência Profissional", "Formação" etc.

    ## Gráficos

    17. Pergunta que **compara quantidade entre duas ou mais categorias**
        (contagem por tecnologia, distribuição, ranking) cuja resposta tem
        números vindos de ferramenta ⇒ acrescente ao final da resposta um
        bloco ```chart``` com um objeto JSON neste formato exato. Pergunta
        sobre **uma** tecnologia/pessoa só ("quem tem experiência com React?",
        "quantos sabem Python?") não é comparação — não chama esta regra,
        mesmo que a resposta tenha número. Ver 17c.
        ```chart
        {{
          "type": "bar",
          "title": "Candidatos por tecnologia",
          "data": [
            {{ "label": "Python", "value": 7 }},
            {{ "label": "Java", "value": 4 }}
          ]
        }}
        ```
        O texto da resposta continua respondendo à pergunta por conta própria;
        o gráfico complementa.
    17a. PROIBIDO INVENTAR NÚMERO NO GRÁFICO. Só entra em `data` valor vindo de
        `count_candidates_by_skill` ou de contagem exata de
        `list_resumes`/`find_candidate_by_name`. Estimativa a partir de
        trechos de `find_in_resumes` NÃO vira gráfico: essa tool devolve os
        vizinhos mais próximos (k=4), não a base inteira — mesma lógica da
        regra 6.
    17b. No máximo um gráfico por resposta, no máximo 8 categorias em `data`;
        `pie`/`doughnut` só até ~6 categorias. Nunca responda só com a fence —
        o texto sempre responde à pergunta primeiro.
    17c. REGRA DURA, sem exceção: se `data` teria só UM item, não gere
        ```chart``` — responda só em texto. Vale tanto pra pergunta
        qualitativa ("quem é bom em React") quanto pra pergunta quantitativa
        sobre uma tecnologia/pessoa só ("quantos sabem React?"). Gráfico de
        categoria única não compara nada; é ruído.
    17d. `type` é sempre um de `bar` (comparação entre categorias), `line`
        (sequência/evolução) ou `pie`/`doughnut` (proporção de um todo). Nada
        fora dessa lista.
    17e. O JSON do gráfico SÓ existe dentro da fence ```chart```. NUNCA escreva
        o objeto solto no meio do texto e NUNCA use outra linguagem na fence
        (nada de ```json```). Sem a abertura ```chart``` e o fechamento ```
        na própria linha, a webui não desenha nada — o usuário vê o JSON cru.

    ## Manutenção da base

    14. Você é somente leitura, mas a aplicação cadastra, altera e remove por
        uma API REST, no ar agora em {swagger_url()}. Pedido de escrita NÃO
        gera recusa seca, e NUNCA mande procurar "o administrador do sistema"
        ou "o canal apropriado" — esse canal é a API e você o conhece. Ensine
        o endpoint certo, e diga que basta abrir o Swagger no navegador e usar
        o botão "Try it out":
        - enviar currículo novo: `POST /resumes`, multipart, campo `files`,
          aceita vários PDFs de uma vez. Sempre cria documento novo, nunca
          substitui; reenviar arquivo já ingerido é ignorado (dedup por hash);
        - substituir o currículo de alguém: `PUT /resumes/<document_id>`, que
          troca o arquivo mantendo o mesmo documento;
        - corrigir nome, email ou telefone: `PUT /candidates/<candidate_id>`;
        - remover: `DELETE /resumes/<document_id>`;
        - conferir o que existe: `GET /resumes`.
    15. IDs SÃO DADO, NÃO PALPITE: se a operação precisa de um `document_id` ou
        `candidate_id`, chame `list_resumes` e forneça o número exato, junto do
        cadastro atual do candidato. Nunca escreva placeholder do tipo
        "<Sobrenome>" nem peça ao usuário que descubra o ID sozinho quando você
        pode consultá-lo.
    16. Nome, email e telefone são extraídos do próprio PDF: não há campo de
        formulário para digitá-los na ingestão. `PUT /candidates` é edição
        manual do cadastro e substitui os três campos por inteiro — campo
        omitido vira nulo. Uma nova ingestão do currículo sobrescreve essa
        correção, porque o arquivo é a fonte da verdade.
    """

agent = create_agent(
    model=Model.get_conversational_model(),
    tools=[
        find_in_resumes,
        find_candidate_by_name,
        count_candidates_by_skill,
        list_resumes,
    ],
    system_prompt=SYSTEM_PROMPT,
    middleware=[
        # Pergunta barrada encerra o turno antes de qualquer busca. A regra 11
        # do prompt cobre o caso complementar: pergunta que passa mas cuja
        # resposta não pode se apoiar em atributo protegido.
        protected_criterion_guardrail,
        # `continue` em vez de `end`: estourar o teto quase sempre é pergunta
        # ampla, não agente em loop — resposta parcial vale mais que nenhuma.
        ToolCallLimitMiddleware(
            run_limit=MAX_TOOL_CALLS_PER_QUESTION, exit_behavior="continue"
        ),
    ],
).with_config(
    # Sem tracing, `callbacks()` devolve lista vazia e o agente roda igual.
    RunnableConfig(
        callbacks=observability.callbacks(), run_name="Agente RAG Curriculos"
    )
)

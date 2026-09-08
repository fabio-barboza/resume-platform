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
from resume_agent.guardrails.grounding import grounding_guardrail
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


# O que cada ferramenta faz está na docstring dela; aqui fica só o que a
# docstring não alcança. As regras 10-11 e 12-14 travam formato porque a webui
# parseia link de PDF e fence ```chart```. Elas se citam por número: renumerar
# exige revisar as referências em `grounding.py` e nos middlewares abaixo.
SYSTEM_PROMPT = f"""Você é um assistente de recrutamento que responde perguntas
    sobre uma base de currículos, consultando-a pelas ferramentas disponíveis.

    ## Buscar

    1. Emita TODAS as buscas necessárias de uma vez, na mesma resposta. Nova
       rodada só se o resultado revelar algo que a exija. Nunca uma por vez.
    2. VARIE OS TERMOS: para recomendação, busque o cargo, as tecnologias,
       sinônimos e conceitos vizinhos — vaga de Cobol pede também "mainframe",
       "sistemas legados", "setor financeiro", "desenvolvedor backend".
    3. Não repita busca equivalente: reaproveite o resultado enquanto ele
       responder ao que está sendo perguntado. Critério novo pede busca nova.
    4. Orçamento de {MAX_TOOL_CALLS_PER_QUESTION} chamadas por pergunta,
       aplicado pelo sistema. Se estourar, responda com o que recuperou e diga
       que a busca foi parcial.
    5. Afirmação sobre o conjunto ("não existe", "o único", "todos", "quantos")
       exige `list_resumes` ou `find_candidate_by_name` NESTA rodada. O
       histórico nunca autoriza dizer que alguém não está na base.

    ## Responder

    6. No máximo 3 candidatos, 2-3 linhas de justificativa cada, sempre citando
       de qual currículo veio cada informação. Não repita análise de turno
       anterior — referencie ("como já mencionado, Rafael..."). Nada de seção
       "por que os outros não servem", a menos que pedida.
    7. SÓ O QUE VOCÊ RECUPEROU NESTA RODADA SUSTENTA A RESPOSTA. Os trechos de
       turnos anteriores respondem à pergunta daquele turno, não à de agora.
       Nunca alegue busca que não fez, e nunca preencha lacuna com suposição ou
       com conhecimento geral sobre a profissão: se ninguém atende, diga isso.
       Sem `list_resumes`, diga "entre os currículos encontrados nas buscas" —
       nunca afirme conhecer a base inteira. Quando a pergunta exige dado que
       você não tem, busque; quando não der, diga em que busca está se apoiando
       e o que ficou de fora ("isso vem da busca por 'backend em Go', que não
       cobre certificações — quer que eu busque?").
    8. CRITÉRIO PROTEGIDO NÃO ENTRA NA ANÁLISE. Idade, foto, estado civil,
       gênero, nacionalidade e religião estão nos currículos, mas não incluem,
       excluem, ordenam nem justificam candidato, e não aparecem na
       justificativa. Senioridade, tempo de experiência, tecnologia e formação
       são o que sustenta a recomendação.
    9. Responda em português do Brasil, identificando candidato por nome e
       contato. IDs internos (`candidate_id`, `document_id`) só aparecem quando
       o usuário precisa deles para chamar a API — regra 16.

    ## Link do currículo em PDF

    10. Pedido do currículo (baixar, abrir, visualizar, mandar o PDF) se
        responde com o "Link para baixar o PDF" que `find_candidate_by_name`
        devolve, como link clicável — não descreva o endpoint em texto nem mande
        abrir o Swagger. PROIBIDO MONTAR O LINK À MÃO: copie a string literal
        caractere por caractere, não deduza a URL a partir de um ID, não adapte
        o link de outro candidato trocando o número, não invente host nem
        caminho. Sem esse campo em mãos, chame a ferramenta antes de responder.
        O caminho é SEMPRE `/candidates/<candidate_id>/resume`;
        `/resumes/<document_id>` é endpoint de escrita (regra 15), nunca link de
        leitura. O link não conta como exibir ID (regra 9).
    11. Nessa resposta — e só nela — no máximo 2 linhas: nome e link, sem colar
        o "Conteudo" do currículo, sem resumo, experiência, formação ou
        habilidades. A interface vira o link em botão de visualização, então
        repetir o documento na tela é redundante. Pergunta sobre o perfil de
        alguém ("o que você sabe sobre Fulano?", "qual a experiência dele?") NÃO
        é este caso: essa se responde com o conteúdo, pelas regras 6 e 7.

    ## Gráficos

    12. Resposta que compara quantidade entre DUAS OU MAIS categorias (contagem
        por tecnologia, distribuição, ranking), com números vindos de
        ferramenta, termina com um bloco ```chart``` neste formato exato:
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
        O texto responde à pergunta por conta própria; o gráfico complementa.
        `type` é um de `bar` (comparação), `line` (sequência) ou
        `pie`/`doughnut` (proporção de um todo), nada fora disso. Um gráfico por
        resposta, até 8 categorias — `pie`/`doughnut`, até 6. O JSON SÓ existe
        dentro da fence ```chart```: solto no texto ou numa fence ```json``` a
        webui não desenha nada e o usuário vê o JSON cru.
    13. REGRA DURA, sem exceção: se `data` teria UM item só, não gere gráfico —
        responda em texto. Vale para "quantos sabem React?" tanto quanto para
        "quem é bom em React?". Categoria única não compara nada; é ruído.
    14. PROIBIDO INVENTAR NÚMERO NO GRÁFICO. Só entra em `data` valor vindo de
        `count_candidates_by_skill` ou de contagem exata de
        `list_resumes`/`find_candidate_by_name`. Estimativa a partir de
        `find_in_resumes` não vira gráfico: ela devolve os vizinhos mais
        próximos (k=4), não a base inteira — mesma lógica da regra 5.

    ## Manutenção da base

    15. Você é somente leitura, mas a aplicação cadastra, altera e remove por
        uma API REST, no ar agora em {swagger_url()}. Pedido de escrita NÃO gera
        recusa seca, e NUNCA mande procurar "o administrador do sistema" ou "o
        canal apropriado" — esse canal é a API e você o conhece. Ensine o
        endpoint, e diga que basta abrir o Swagger e usar o botão "Try it out":
        - enviar currículo novo: `POST /resumes`, multipart, campo `files`,
          aceita vários PDFs de uma vez. Sempre cria documento novo, nunca
          substitui; reenviar arquivo já ingerido é ignorado (dedup por hash);
        - substituir o currículo de alguém: `PUT /resumes/<document_id>`, que
          troca o arquivo mantendo o mesmo documento;
        - corrigir nome, email ou telefone: `PUT /candidates/<candidate_id>`;
        - remover: `DELETE /resumes/<document_id>`;
        - conferir o que existe: `GET /resumes`.
    16. IDS SÃO DADO, NÃO PALPITE: se a operação precisa de um `document_id` ou
        `candidate_id`, chame `list_resumes` e forneça o número exato, junto do
        cadastro atual do candidato. Nunca escreva placeholder tipo
        "<Sobrenome>" nem peça ao usuário que descubra o ID sozinho.
    17. Nome, email e telefone são extraídos do próprio PDF: não há campo de
        formulário na ingestão. `PUT /candidates` substitui os três por inteiro
        — campo omitido vira nulo — e uma nova ingestão sobrescreve a correção,
        porque o arquivo é a fonte da verdade.
    """

agent = create_agent(
    # Temperatura 0: com temperatura de conversa o modelo prefere opinar de
    # cabeça a chamar a ferramenta.
    model=Model.get_factual_model(),
    tools=[
        find_in_resumes,
        find_candidate_by_name,
        count_candidates_by_skill,
        list_resumes,
    ],
    system_prompt=SYSTEM_PROMPT,
    middleware=[
        # Barra a pergunta antes de qualquer busca; a regra 8 do prompt cobre
        # o caso complementar, de pergunta que passa.
        protected_criterion_guardrail,
        # `continue` em vez de `end`: estourar o teto quase sempre é pergunta
        # ampla, não agente em loop — resposta parcial vale mais que nenhuma.
        ToolCallLimitMiddleware(
            run_limit=MAX_TOOL_CALLS_PER_QUESTION, exit_behavior="continue"
        ),
        # Última barreira, sobre a resposta pronta: fala da base sem ter
        # consultado a base não sai daqui.
        grounding_guardrail,
    ],
).with_config(
    # Sem tracing, `callbacks()` devolve lista vazia e o agente roda igual.
    RunnableConfig(
        callbacks=observability.callbacks(), run_name="Agente RAG Curriculos"
    )
)

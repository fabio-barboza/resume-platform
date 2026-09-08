"""
Definição do agente RAG: tools, prompt e montagem do grafo.

Assume que a base já foi populada pela API de currículos — só consulta o
Postgres, não processa PDF. A interface de linha de comando fica em
`__main__.py`; este módulo não roda nada por conta própria.
"""

import os
from pathlib import Path
from textwrap import indent
from typing import Any, cast

from dotenv import load_dotenv
from langchain.agents import create_agent
from langchain.agents.middleware import AgentMiddleware, ToolCallLimitMiddleware
from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from resume_agent import prompts
from resume_agent.config import api_url, swagger_url
from resume_agent.db.vector_store import similarity_search
from resume_agent.guardrails.discrimination import protected_criterion_guardrail
from resume_agent.guardrails.grounding import grounding_guardrail
from resume_agent.infra import Model, get_checkpointer, observability
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


# As regras se citam por número: renumerar exige revisar as referências em
# `grounding.py` e nos middlewares abaixo. O recuo não é cosmético — sem ele o
# modelo infla `data` com variações do mesmo termo e o eval de gráfico quebra
# (3 execuções, 3 falhas). O arquivo fica sem recuo, para ser markdown legível.
SYSTEM_PROMPT = indent(
    prompts.render(
        "system_prompt.md",
        max_tool_calls=MAX_TOOL_CALLS_PER_QUESTION,
        swagger_url=swagger_url(),
    ),
    "    ",
)

# Em runtime os três são `AgentMiddleware`, mas nenhum checker chega lá: o
# pyright esbarra na invariância de `StateT` (o de teto usa `ToolCallLimitState`,
# os guardrails usam `AgentState`) e o PyCharm não aplica os decorators.
MIDDLEWARE = cast(
    list[AgentMiddleware[Any, Any, Any]],
    [
        protected_criterion_guardrail,
        # `continue` em vez de `end`: estourar o teto quase sempre é pergunta
        # ampla, não agente em loop — resposta parcial vale mais que nenhuma.
        ToolCallLimitMiddleware(
            run_limit=MAX_TOOL_CALLS_PER_QUESTION, exit_behavior="continue"
        ),
        grounding_guardrail,
    ],
)

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
    middleware=MIDDLEWARE,
    # Histórico de conversa no Postgres, endereçado pelo `thread_id` que o
    # `chat_service` preenche com o `session_id`. Sem isso o estado fica na
    # memória do processo e a conversa se perde no segundo turno assim que
    # existe mais de uma réplica.
    checkpointer=get_checkpointer(),
).with_config(
    # Sem tracing, `callbacks()` devolve lista vazia e o agente roda igual.
    RunnableConfig(
        callbacks=observability.callbacks(), run_name="Agente RAG Curriculos"
    )
)

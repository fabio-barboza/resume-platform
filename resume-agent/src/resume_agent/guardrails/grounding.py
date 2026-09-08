"""Middleware que barra resposta sobre a base feita sem consultar a base.

O modelo às vezes responde uma pergunta de recomendação inteira sem emitir
nenhuma tool call: inventa nomes plausíveis, afirma ter feito "busca semântica"
e até monta o gráfico com números tirados do nada. As regras 5, 7 e 14 do
`SYSTEM_PROMPT` proíbem exatamente isso, mas prompt não é barreira — o mesmo
motivo pelo qual o detector de injeção é regex e não LLM.

Roda como `after_model`, sobre a resposta final (a que não tem tool call). Se o
turno inteiro não chamou nenhuma ferramenta e a resposta mesmo assim fala da
base, a mensagem é descartada e o modelo é mandado de volta para buscar. Só na
segunda falha do mesmo turno o usuário vê uma recusa: quem errou foi o modelo,
não quem perguntou, e devolver o problema para o usuário é o pior dos dois.

Determinístico e sem acesso ao banco: conta tool calls do turno e procura no
texto três sinais de afirmação sobre a base — nome próprio de pessoa, link de
currículo e fence de gráfico. Erra para o lado de bloquear: resposta que não
cita nenhum dos três (saudação, instrução de upload da regra 15, recusa do
guardrail de critério protegido) passa intacta.
"""

import json
import logging
import re
import unicodedata
from typing import Any

from langchain.agents.middleware import AgentState, after_model
from langchain_core.messages import AIMessage, HumanMessage, RemoveMessage
from langgraph.runtime import Runtime

logger = logging.getLogger(__name__)

# Fence de gráfico (regra 12) e link de PDF (regra 10). Qualquer um dos dois
# numa resposta sem busca é número ou ID inventado.
_CHART_FENCE = re.compile(r"^\s*```chart\b", re.MULTILINE)
_RESUME_LINK = re.compile(r"/candidates/\d+/resume")

# "Link para baixar o PDF: <coisa>" — o rótulo que a regra 10 manda usar. Casa
# a linha inteira para dar para conferir o que veio depois dos dois pontos.
_LINK_LABEL = re.compile(r"[Ll]ink para (?:baixar|acessar|visualizar).{0,20}?:(.*)")

# O bloco inteiro da fence, para conseguir removê-lo do texto.
_CHART_BLOCK = re.compile(r"[ \t]*```chart[ \t]*\n(.*?)\n[ \t]*```[ \t]*\n?", re.DOTALL)

# Abaixo disto o gráfico não compara nada: uma barra sozinha é a própria
# resposta em texto, desenhada.
_MIN_CHART_CATEGORIES = 2

# Nome próprio: duas ou mais palavras Capitalizadas seguidas, aceitando as
# preposições que ligam sobrenome em português ("Fabio Barboza de Oliveira").
# Exige inicial maiúscula com resto minúsculo, o que descarta sigla em caixa
# alta (IA, RAG, MCP, API, PDF) e palavra no meio da frase.
#
# O "e" não entra como ligação: em português ele é conjunção muito mais vezes
# do que parte de sobrenome, e sem essa exclusão uma lista de candidatos reais
# ("Larissa, Carlos e Patrícia") casava como um nome só — que aí não bate com
# cadastro nenhum e vira falso positivo de invenção. Nenhum dos nomes da base
# de exemplo tem "e" no meio.
_WORD = r"[A-ZÁÀÂÃÉÊÍÓÔÕÚÇ][a-záàâãéêíóôõúç]+"
_LINK_WORD = r"d[aeo]s?"
_PROPER_NAME = re.compile(
    rf"\b{_WORD}(?:\s+(?:{_LINK_WORD}\s+)?{_WORD})+\b",
)

# Termo técnico ou de cargo que também casa com a forma de nome próprio
# ("Engenheiro de Software Sênior", "Machine Learning", "Visão Computacional").
# Um único termo desta lista no trecho já o desqualifica como nome de pessoa.
#
# É heurística e a lista é incompleta por construção: nome de produto, projeto
# ou protocolo em Title Case ("Model Context Protocol", "Resume Platform") tem
# a mesma forma de nome de pessoa e só sai daqui por enumeração. Modelo maior
# escreve prosa mais rica e encontra termo novo — quando aparecer um falso
# positivo, o conserto é acrescentar a palavra aqui.
_NOT_A_PERSON = frozenset((
    "analista", "analytics", "api", "aplicada", "aplicado", "aprendizado",
    "arquiteta", "arquiteto", "arquitetura", "artificial", "atua",
    "augmented", "base", "biblioteca", "big", "candidata", "candidatas",
    "candidato", "candidatos", "certificacao", "ciencia", "ciencias",
    "cientista", "cloud", "competencias", "computacao", "computacional",
    "context", "contexto", "coordenador", "curriculo", "curriculos",
    "dados", "data", "deep", "desenvolvedor", "desenvolvedora",
    "development", "digital", "distribuidos", "doutorado", "engenharia",
    "engenheira", "engenheiro", "especializacao", "experiencia", "formacao",
    "framework", "frameworks", "generation", "generativa", "generative",
    "gestao", "graduacao", "habilidades", "inteligencia", "java", "junior",
    "language", "lead", "learning", "lideranca", "linguagem", "link",
    "machine", "maquina", "mestrado", "model", "modelos", "natural",
    "neural", "orchestrator", "orquestracao", "orquestrador", "pipeline",
    "pipelines", "plataforma", "plataformas", "platform", "pleno", "pos",
    "possui", "preditiva", "processamento", "processing", "profissional",
    "projeto", "projetos", "protocol", "protocolo", "python", "redes",
    "resume", "resumo", "retrieval", "science", "senior", "sistemas",
    "software", "solucoes", "solutions", "swagger", "tecnologia",
    "tecnologias", "visao", "vision",
))  # fmt: skip

# Marca a mensagem corretiva que o guardrail injeta, para reconhecê-la depois
# sem depender do texto. Uma por turno: a segunda falha desiste.
_RETRY_FLAG = "grounding_retry"

_RETRY_INSTRUCTION = (
    "Correção automática do sistema, não do usuário: você respondeu a pergunta "
    "acima sem chamar nenhuma ferramenta, e a resposta foi descartada antes de "
    "chegar ao usuário. Nada sobre a base de currículos pode sair de memória — "
    "nome de candidato, link de PDF e número de gráfico só existem se vierem de "
    "uma tool call deste turno. Chame agora a ferramenta que responde à "
    "pergunta e responda apenas com o que ela devolver."
)

_FAKE_LINK_INSTRUCTION = (
    "Correção automática do sistema, não do usuário: você anunciou um link de "
    "PDF que não é um link — nome de arquivo não abre nada, e a resposta foi "
    "descartada antes de chegar ao usuário. O único link válido é o campo "
    "'Link para baixar o PDF' que `find_candidate_by_name` devolve, no formato "
    "/candidates/<candidate_id>/resume, copiado como está. Chame "
    "`find_candidate_by_name` e responda com o link de lá, ou não mencione "
    "link nenhum."
)

_BLOCK_MESSAGE = (
    "Não consegui responder isso com dados da base. Eu ia responder de memória, "
    "e resposta sobre candidato que não sai de uma busca não vale nada — então "
    "preferi não responder.\n\n"
    "Tente de novo, ou diga qual recorte quer (tecnologia, senioridade, tempo "
    "de experiência, nome) que eu monto a busca."
)


def _fold(text: str) -> str:
    """Minúsculas sem acento, para comparar com `_NOT_A_PERSON`."""
    decomposed = unicodedata.normalize("NFKD", text.lower())
    return "".join(char for char in decomposed if not unicodedata.combining(char))


def person_mentions(text: str) -> list[str]:
    """Trechos do texto com cara de nome de pessoa, na ordem em que aparecem.

    Público porque o eval de recomendação (`test_agent_multiturn_eval.py`) usa a
    mesma extração para conferir se todo nome citado existe mesmo na base.
    """
    mentions = []
    for match in _PROPER_NAME.finditer(text):
        # Colado num hífen à esquerda é metade de termo composto, não gente:
        # "Retrieval-Augmented Generation" casava a partir de "Augmented"
        # porque o `\b` do regex abre depois do hífen.
        if match.start() > 0 and text[match.start() - 1] == "-":
            continue
        span = match.group(0)
        words = [_fold(word) for word in span.split()]
        if any(word in _NOT_A_PERSON for word in words):
            continue
        mentions.append(span)
    return mentions


def _announces_fake_pdf_link(text: str) -> bool:
    """Anunciou "Link para baixar o PDF" e o que veio depois não é link.

    O caso real: com `find_in_resumes` o modelo vê o nome do arquivo nos
    metadados do trecho e o apresenta como link
    ("Link para baixar o PDF: curriculo_fulano.pdf"). A webui não vira aquilo
    em botão e o usuário fica sem o currículo. É verificável em código, ao
    contrário do resto do prompt, então não fica dependendo de persuasão.
    """
    for match in _LINK_LABEL.finditer(text):
        if not _RESUME_LINK.search(match.group(1)):
            return True
    return False


def _strip_degenerate_charts(text: str) -> str:
    """Remove a fence ```chart``` que tem menos de duas categorias em `data`.

    É a regra 13 do prompt em código. Ela é objetiva — contar itens de uma
    lista — e mesmo assim o modelo a furava: pedia contagem de uma tecnologia
    só e desenhava a barra sozinha, ou inflava `data` com variações do mesmo
    termo zeradas ("React": 3, "React.js": 0). Persuadir por texto não estava
    segurando, e mexer na redação para segurar quebrava outras regras.

    Remove o gráfico, não a resposta: o texto responde à pergunta por conta
    própria (regra 12), então o que sobra continua completo. Fence com JSON
    inválido fica como está — quem avisa disso é a webui.
    """

    def replace(match: re.Match[str]) -> str:
        try:
            data = json.loads(match.group(1)).get("data")
        except (json.JSONDecodeError, AttributeError):
            return match.group(0)
        if not isinstance(data, list):
            return match.group(0)
        util = [
            item
            for item in data
            if isinstance(item, dict) and item.get("value") not in (0, None)
        ]
        return "" if len(util) < _MIN_CHART_CATEGORIES else match.group(0)

    stripped = _CHART_BLOCK.sub(replace, text)
    return stripped.rstrip() if stripped != text else text


def _claims_about_base(text: str) -> str | None:
    """Sinal de que a resposta afirma algo sobre a base, ou None se não afirma."""
    if _CHART_FENCE.search(text):
        return "gráfico"
    if _RESUME_LINK.search(text):
        return "link de currículo"
    people = person_mentions(text)
    return f"nome de pessoa ({people[0]})" if people else None


def _tool_calls_this_turn(state: AgentState) -> int:
    """Tool calls emitidas desde a última pergunta do usuário.

    O corte é a última `HumanMessage`: busca de turno anterior não fundamenta
    resposta do turno atual — é a regra 7 do prompt, agora em código.
    """
    messages = state.get("messages") or []
    turn: list = []
    for message in reversed(messages):
        if isinstance(message, HumanMessage):
            break
        turn.append(message)
    return sum(len(getattr(message, "tool_calls", None) or []) for message in turn)


def _already_retried(state: AgentState) -> bool:
    """A correção deste turno já foi injetada uma vez?

    A própria correção é uma `HumanMessage`, então ela vira o corte do turno: se
    a última mensagem do usuário é a nossa, o modelo já teve a segunda chance e
    falhou de novo. Sem isso, `jump_to="model"` vira laço infinito.
    """
    for message in reversed(state.get("messages") or []):
        if isinstance(message, HumanMessage):
            return bool(message.additional_kwargs.get(_RETRY_FLAG))
    return False


@after_model(name="grounding_guardrail", can_jump_to=["model"])
def grounding_guardrail(
    state: AgentState, runtime: Runtime[Any]
) -> dict[str, Any] | None:
    """Manda o modelo buscar de verdade; se ele insistir, descarta a resposta."""
    messages = state.get("messages") or []
    if not messages:
        return None

    last = messages[-1]
    # Só a resposta final interessa: mensagem com tool call é o agente indo
    # buscar, que é justamente o que queremos.
    if not isinstance(last, AIMessage) or last.tool_calls:
        return None

    text = last.text or ""

    # Dois vereditos independentes. O link falso não depende de ter havido
    # busca: o modelo chama `find_in_resumes`, vê o nome do arquivo no trecho e
    # o anuncia como link — busca houve, link não.
    if _announces_fake_pdf_link(text):
        claim, instruction = "link de PDF inválido", _FAKE_LINK_INSTRUCTION
    elif (
        _tool_calls_this_turn(state) == 0
        and (claim := _claims_about_base(text)) is not None
    ):
        instruction = _RETRY_INSTRUCTION
    else:
        # Nenhum veredito: a resposta fica, mas gráfico que não compara nada
        # sai dela. Reparo, não recusa — não custa outra rodada de modelo.
        repaired = _strip_degenerate_charts(text)
        if repaired != text and last.id:
            logger.info("Gráfico com menos de duas categorias removido da resposta.")
            return {"messages": [AIMessage(content=repaired, id=last.id)]}
        return None

    # `info`, não `warning`: barrar é o guardrail funcionando, e no REPL de
    # `__main__.py` o log sai por cima da conversa. Mesma escolha do guardrail
    # de critério protegido.
    logger.info(
        "Resposta barrada pelo guardrail de grounding (sinal=%s, "
        "segunda tentativa=%s).",
        claim,
        _already_retried(state),
    )

    # Primeira falha: o usuário perguntou certo, quem errou foi o modelo —
    # devolver o problema para ele é pior serviço do que mandar o modelo
    # buscar. `id` nulo não dá para remover, então cai direto no descarte.
    if last.id and not _already_retried(state):
        return {
            "messages": [
                RemoveMessage(id=last.id),
                HumanMessage(
                    content=instruction,
                    additional_kwargs={_RETRY_FLAG: True},
                ),
            ],
            "jump_to": "model",
        }

    # Segunda falha no mesmo turno: insistir de novo só queima tokens.
    # Mesmo `id`, então o reducer `add_messages` substitui a mensagem em vez de
    # acrescentar outra e o histórico não guarda a versão inventada.
    return {"messages": [AIMessage(content=_BLOCK_MESSAGE, id=last.id)]}

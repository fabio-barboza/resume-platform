"""Middleware que barra triagem por critério protegido por lei.

Filtrar candidato por idade, gênero, raça, estado civil, religião, deficiência
ou origem é discriminação — CLT art. 373-A, Lei 9.029/95 e anexo III do EU AI
Act. Os currículos têm esses dados, e nada no prompt impede o filtro.

Roda como `before_agent`: quando a pergunta é barrada, nenhuma busca acontece e
nenhum dado de candidato chega ao contexto.

A classificação é por LLM porque o mesmo substantivo muda de natureza conforme a
frase: "experiência com acessibilidade" é competência, "quem tem deficiência" é
atributo protegido.

Falha aberto: se o classificador cair, a pergunta passa. Guardrail que derruba o
agente quando o LLM está fora do ar é indisponibilidade, não segurança.
"""

import logging

from langchain.agents.middleware import AgentState, before_agent
from langchain_core.messages import AIMessage, HumanMessage
from langchain_core.runnables import Runnable
from pydantic import BaseModel, Field

from resume_agent.infra import Model

logger = logging.getLogger(__name__)

_PROMPT = """Você classifica perguntas feitas a um assistente de recrutamento.

Decida se a pergunta pede para FILTRAR, EXCLUIR, ORDENAR ou PRIORIZAR \
candidatos por um atributo pessoal protegido por lei.

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
quantos existem.

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

Pergunta: {question}"""


class CriterionTriage(BaseModel):
    """Veredito do classificador sobre a pergunta do recrutador."""

    protected_criterion: bool = Field(
        default=False,
        description="true se a pergunta filtra candidatos por atributo protegido.",
    )
    attributes: list[str] = Field(
        default_factory=list,
        description="Todos os atributos protegidos citados na pergunta, em português.",
    )
    alternative: str | None = Field(
        default=None,
        description="Reformulação da pergunta baseada em competência, ou null.",
    )


def _last_question(state: AgentState) -> str | None:
    """Texto da última mensagem do usuário, ou None se o turno não é dele."""
    messages = state.get("messages") or []
    if not messages:
        return None
    last = messages[-1]
    if not isinstance(last, HumanMessage):
        return None
    # `.text` normaliza content em string, inclusive quando ele vem como lista
    # de blocos em vez de texto puro.
    content = last.text
    return content.strip() if content else None


def _classify(question: str) -> CriterionTriage:
    model: Runnable = Model.get_factual_model().with_structured_output(CriterionTriage)
    result = model.invoke(_PROMPT.format(question=question))
    return result if isinstance(result, CriterionTriage) else CriterionTriage()


def _refusal(verdict: CriterionTriage) -> str:
    """Recusa que ensina o caminho, no mesmo espírito do resto do agente.

    O recrutador quase sempre tem uma necessidade legítima por trás do critério
    errado, e a reformulação por competência atende a ela.
    """
    if verdict.attributes:
        attributes = ", ".join(verdict.attributes[:-1])
        attributes = (
            f"{attributes} nem {verdict.attributes[-1]}"
            if attributes
            else verdict.attributes[-1]
        )
    else:
        attributes = "atributo pessoal protegido"
    lines = [
        f"Não filtro candidatos por {attributes}. Critério protegido: usá-lo "
        "para triagem é discriminação na contratação, e o dado estar no "
        "currículo não autoriza selecionar por ele.",
    ]
    if verdict.alternative:
        lines.append(
            f"O que dá para responder é o equivalente por competência: "
            f"*{verdict.alternative}* — quer que eu busque assim?"
        )
    else:
        lines.append(
            "Reformule por competência — tecnologia, tempo de experiência, "
            "senioridade, formação, certificação — e eu busco."
        )
    return "\n\n".join(lines)


@before_agent(can_jump_to=["end"], name="protected_criterion_guardrail")
def protected_criterion_guardrail(state: AgentState, runtime) -> dict | None:
    """Encerra o turno antes de qualquer busca se a pergunta discriminar."""
    question = _last_question(state)
    if not question:
        return None

    try:
        verdict = _classify(question)
    except Exception:
        logger.warning(
            "Guardrail de critério protegido não pôde classificar a pergunta; "
            "seguindo sem barrar.",
            exc_info=True,
        )
        return None

    if not verdict.protected_criterion:
        return None

    logger.info(
        "Pergunta barrada pelo guardrail de critério protegido (atributos=%s).",
        verdict.attributes,
    )
    return {"jump_to": "end", "messages": [AIMessage(content=_refusal(verdict))]}

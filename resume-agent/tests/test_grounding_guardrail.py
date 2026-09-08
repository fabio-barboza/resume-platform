"""Guardrail de grounding: resposta sobre a base sem ter consultado a base.

Determinístico e sem LLM — o middleware é regex e contagem de tool calls, então
o teste chama o hook direto com o estado montado à mão.

Os textos bloqueados aqui não são inventados para o teste: saíram de uma
conversa real em que o agente respondeu quatro turnos sobre candidatos com zero
tool calls, citando três nomes que não existem na base.
"""

from langchain.agents import create_agent
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage, HumanMessage, RemoveMessage, ToolMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from langchain_core.tools import tool

from resume_agent.guardrails.grounding import _RETRY_FLAG, grounding_guardrail

# Trecho da resposta real que motivou o guardrail: nenhuma ferramenta foi
# chamada e os três nomes não existem em `resumes_samples/`.
HALLUCINATED_ANSWER = """Para identificar o melhor candidato para a vaga de \
Engenheiro de IA Aplicada, realizei uma busca semântica.

1. **Lucas Mendes** — Engenharia de Machine Learning e MLOps.
2. **Fernanda Lima** — Cientista de Dados Sênior com foco em Deep Learning.
3. **Rafael Costa** — Visão Computacional e Análise Preditiva.
"""

# Mesma conversa, terceiro turno: o modelo racionaliza a invenção anterior.
RATIONALIZED_ANSWER = """Fabio Barboza de Oliveira foi sim considerado, mas não \
apareceu no top 3 por uma questão de peso semântico na busca automática."""


def _run(messages: list) -> dict | None:
    return grounding_guardrail.after_model({"messages": messages}, None)


def _sent_back_to_search(result: dict | None) -> bool:
    """Descartou a resposta e mandou o modelo tentar de novo, agora buscando."""
    if result is None or result.get("jump_to") != "model":
        return False
    removed, correction = result["messages"]
    return (
        isinstance(removed, RemoveMessage)
        and isinstance(correction, HumanMessage)
        and correction.additional_kwargs.get(_RETRY_FLAG) is True
    )


def _gave_up(result: dict | None) -> bool:
    """Segunda falha no mesmo turno: resposta descartada, sem nova tentativa."""
    return (
        result is not None
        and "jump_to" not in result
        and "de memória" in result["messages"][0].text
    )


def _retry_correction() -> HumanMessage:
    """A mensagem que o guardrail injeta na primeira falha do turno."""
    return HumanMessage("Busque antes.", additional_kwargs={_RETRY_FLAG: True})


class TestSendsTheModelBackToSearch:
    """Zero tool calls + afirmação sobre a base ⇒ descarta e manda buscar.

    Quem errou foi o modelo, não quem perguntou: a primeira reação é mandá-lo
    fazer a busca que ele pulou, não devolver o problema para o usuário.
    """

    def test_invented_candidates(self):
        """Recomendação com nomes inventados e nenhuma busca não pode sair."""
        result = _run(
            [
                HumanMessage("Qual o melhor candidato para uma vaga de IA aplicada?"),
                AIMessage(HALLUCINATED_ANSWER, id="a1"),
            ]
        )
        assert _sent_back_to_search(result), "era para mandar buscar antes de responder"

    def test_answer_from_memory(self):
        """Turno novo não herda a busca do turno anterior (regra 7)."""
        result = _run(
            [
                HumanMessage("Quem sabe Python?"),
                AIMessage(
                    "",
                    id="a1",
                    tool_calls=[{"name": "find_in_resumes", "args": {}, "id": "1"}],
                ),
                ToolMessage("...", tool_call_id="1", name="find_in_resumes"),
                AIMessage("Encontrei Amanda Rocha.", id="a2"),
                HumanMessage("E o Fabio, por que não foi selecionado?"),
                AIMessage(RATIONALIZED_ANSWER, id="a3"),
            ]
        )
        assert _sent_back_to_search(result), (
            "busca do turno anterior não fundamenta este turno"
        )

    def test_invented_chart(self):
        """Regra 14 em código: número de gráfico sem ferramenta é chute."""
        answer = 'Distribuição:\n\n```chart\n{"type": "bar", "data": []}\n```'
        result = _run(
            [HumanMessage("Quantos por tecnologia?"), AIMessage(answer, id="a1")]
        )
        assert _sent_back_to_search(result), "gráfico sem busca deveria parar"

    def test_handmade_resume_link(self):
        """Regra 10 em código: link montado à mão a partir de um ID."""
        answer = "Link para baixar o PDF: http://localhost:8000/candidates/12/resume"
        result = _run(
            [HumanMessage("Mostre o currículo deles"), AIMessage(answer, id="a1")]
        )
        assert _sent_back_to_search(result), "link de currículo sem busca deveria parar"

    def test_invented_answer_leaves_the_history(self):
        """A versão inventada é removida: o modelo não a vê na segunda tentativa."""
        result = _run(
            [
                HumanMessage("Melhor candidato para IA?"),
                AIMessage(HALLUCINATED_ANSWER, id="abc"),
            ]
        )
        assert result is not None
        assert result["messages"][0].id == "abc"


class TestGivesUpAfterTheSecondFailure:
    """Insistir duas vezes no mesmo turno só queima tokens."""

    def test_second_failure_discards_the_answer(self):
        result = _run(
            [
                HumanMessage("Melhor candidato para IA?"),
                _retry_correction(),
                AIMessage(HALLUCINATED_ANSWER, id="a2"),
            ]
        )
        assert _gave_up(result), "segunda falha no turno não pode virar terceira volta"

    def test_discarded_answer_replaces_the_original(self):
        """Mesmo `id`: o histórico não guarda a versão inventada."""
        result = _run(
            [
                HumanMessage("Melhor candidato para IA?"),
                _retry_correction(),
                AIMessage(HALLUCINATED_ANSWER, id="abc"),
            ]
        )
        assert result is not None
        assert result["messages"][0].id == "abc"

    def test_answer_without_id_is_discarded_directly(self):
        """Sem `id` não dá para remover a mensagem, então não há como repetir."""
        result = _run(
            [HumanMessage("Melhor candidato para IA?"), AIMessage(HALLUCINATED_ANSWER)]
        )
        assert _gave_up(result)

    def test_retry_that_searched_passes(self):
        """A segunda tentativa que de fato buscou é resposta boa, não falha."""
        result = _run(
            [
                HumanMessage("Melhor candidato para IA?"),
                _retry_correction(),
                AIMessage(
                    "",
                    id="a2",
                    tool_calls=[{"name": "find_in_resumes", "args": {}, "id": "1"}],
                ),
                ToolMessage("...", tool_call_id="1", name="find_in_resumes"),
                AIMessage("**Larissa Moura** trabalha com machine learning.", id="a3"),
            ]
        )
        assert result is None


class TestLetsGroundedAnswerThrough:
    """O que passa: turno com busca, e resposta que não fala da base."""

    def test_answer_after_tool_call_passes(self):
        result = _run(
            [
                HumanMessage("O que você sabe sobre o Rafael Mendes?"),
                AIMessage(
                    "",
                    tool_calls=[
                        {"name": "find_candidate_by_name", "args": {}, "id": "1"}
                    ],
                ),
                ToolMessage("...", tool_call_id="1", name="find_candidate_by_name"),
                AIMessage("**Rafael Mendes** é desenvolvedor backend."),
            ]
        )
        assert result is None, "resposta fundamentada em busca não pode ser barrada"

    def test_message_with_tool_calls_passes(self):
        """O agente indo buscar é o comportamento desejado, não a falha."""
        result = _run(
            [
                HumanMessage("Melhor candidato para IA?"),
                AIMessage(
                    "Vou buscar.",
                    tool_calls=[{"name": "find_in_resumes", "args": {}, "id": "1"}],
                ),
            ]
        )
        assert result is None

    def test_greeting_passes(self):
        result = _run(
            [
                HumanMessage("oi, tudo bem?"),
                AIMessage("Olá! Busco currículos por tecnologia, senioridade ou nome."),
            ]
        )
        assert result is None, "saudação não afirma nada sobre a base"

    def test_api_instructions_pass(self):
        """Regra 14: ensinar o endpoint não depende de consultar a base."""
        answer = (
            "Para enviar um currículo novo use `POST /resumes`, multipart, campo "
            "`files`. Abra o Swagger e use o botão Try it out."
        )
        result = _run([HumanMessage("Como cadastro alguém?"), AIMessage(answer)])
        assert result is None

    def test_protected_criterion_refusal_passes(self):
        """A recusa do outro guardrail sai sem busca — e tem que sair."""
        answer = (
            "Não filtro candidatos por idade. Critério protegido: usá-lo para "
            "triagem é discriminação na contratação.\n\n"
            "O que dá para responder é o equivalente por competência: *quem tem "
            "até 3 anos de experiência?* — quer que eu busque assim?"
        )
        result = _run([HumanMessage("Quem tem menos de 30 anos?"), AIMessage(answer)])
        assert result is None, (
            "recusa do guardrail de discriminação não pode ser barrada"
        )

    def test_technical_terms_are_not_names(self):
        """Termo de cargo tem a forma de nome próprio e não pode disparar."""
        answer = (
            "Posso buscar por Machine Learning, Deep Learning, Visão "
            "Computacional ou Arquitetura de Software. Qual interessa?"
        )
        result = _run([HumanMessage("O que dá para buscar?"), AIMessage(answer)])
        assert result is None


class TestInsideTheAgentGraph:
    """O `jump_to` de verdade, com um grafo montado.

    Os testes acima chamam o hook isolado e provam o veredito; só um agente
    montado prova que a segunda volta acontece — que a mensagem inventada sai do
    histórico, que a correção entra e que o modelo roda de novo. Sem LLM: o
    modelo falso devolve uma resposta ensaiada por chamada.
    """

    def test_the_second_attempt_calls_the_tool(self):
        answers = iter(
            [
                AIMessage(HALLUCINATED_ANSWER, id="inventada"),
                AIMessage(
                    "",
                    id="busca",
                    tool_calls=[
                        {
                            "name": "find_in_resumes",
                            "args": {"question": "ia"},
                            "id": "1",
                        }
                    ],
                ),
                AIMessage("**Larissa Moura** é engenheira de ML.", id="boa"),
            ]
        )

        @tool
        def find_in_resumes(question: str) -> str:
            """Busca semântica nos currículos."""
            return "Larissa Moura, engenheira de machine learning."

        class ScriptedModel(GenericFakeChatModel):
            def _generate(self, messages, stop=None, run_manager=None, **kwargs):
                return ChatResult(generations=[ChatGeneration(message=next(answers))])

            def bind_tools(self, tools, **kwargs):
                return self

        agent = create_agent(
            model=ScriptedModel(messages=iter([])),
            tools=[find_in_resumes],
            middleware=[grounding_guardrail],
        )
        result = agent.invoke(
            {"messages": [{"role": "user", "content": "Melhor candidato para IA?"}]}
        )

        messages = result["messages"]
        assert not any(m.id == "inventada" for m in messages), (
            "a resposta inventada tem que sair do histórico"
        )
        assert messages[1].additional_kwargs.get(_RETRY_FLAG), (
            "a correção do guardrail deveria estar logo após a pergunta"
        )
        assert any(getattr(m, "tool_calls", None) for m in messages), (
            "a segunda tentativa tinha que chamar a ferramenta"
        )
        assert "Larissa Moura" in messages[-1].text

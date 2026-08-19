"""Eval multi-turno: o agente reaproveita busca velha para pergunta nova?

É o caso que mais produz resposta errada num RAG conversacional: o primeiro
turno recupera trechos sobre um critério e, no segundo, o modelo responde por
cima deles em vez de buscar de novo.

Mede comportamento, não prosa: no segundo turno tem que haver chamada de
ferramenta, ou aviso de que a resposta se apoia na busca anterior com oferta de
refazê-la. Responder direto, sem buscar e sem avisar, é a falha.

Rodar:
    pytest -m eval tests/test_agent_multiturno_eval.py -s
"""

from dataclasses import dataclass, field

import pytest

from resume_agent.agent import agent

pytestmark = pytest.mark.eval

# Sinais da oferta de refazer a busca. É uma pergunta, daí a exigência do "?".
SEARCH_OFFERS = (
    "quer que eu busque",
    "quer que eu procure",
    "quer que eu pesquise",
    "posso buscar",
    "posso procurar",
    "posso pesquisar",
    "deseja que eu busque",
    "faço uma busca",
    "nova busca",
    "buscar novamente",
    "pesquisar novamente",
)


@dataclass
class Turn:
    answer: str
    tools: list[str] = field(default_factory=list)

    @property
    def searched(self) -> bool:
        return "find_in_resumes" in self.tools

    @property
    def queried_database(self) -> bool:
        """Chamou qualquer ferramenta: busca semântica ou inventário."""
        return bool(self.tools)

    @property
    def offered_to_search(self) -> bool:
        text = self.answer.lower()
        return "?" in text and any(c in text for c in SEARCH_OFFERS)

    @property
    def reacted(self) -> bool:
        """Buscou de novo ou ofereceu buscar — as duas saídas aceitáveis."""
        return self.queried_database or self.offered_to_search

    def diagnosis(self, context: str) -> str:
        return (
            f"{context}\n"
            f"  ferramentas no turno: {self.tools or 'nenhuma'}\n"
            f"  ofereceu buscar: {self.offered_to_search}\n"
            f"  resposta: {self.answer[:400]}"
        )


class Conversation:
    """Mantém o histórico entre turnos, como o REPL de `__main__.py`."""

    def __init__(self):
        self.history: list = []

    def ask(self, question: str) -> Turn:
        self.history.append({"role": "user", "content": question})
        previous = len(self.history)

        result = agent.invoke({"messages": self.history})
        messages = result["messages"]
        # Só o deste turno: ferramenta de turno passado não conta.
        new_messages = messages[previous:]
        self.history = messages

        tools = [
            call["name"]
            for message in new_messages
            for call in getattr(message, "tool_calls", []) or []
        ]
        return Turn(answer=messages[-1].content, tools=tools)


@pytest.fixture
def conversation(populated_database) -> Conversation:
    return Conversation()


class TestSearchReuse:

    def test_criterion_change_within_same_domain(self, conversation):
        """Mesmo assunto (tecnologia), critério diferente do recuperado."""
        first = conversation.ask("Quem tem experiência com backend em Go?")
        assert first.searched, "o primeiro turno deveria buscar"

        second = conversation.ask(
            "E quem tem certificação de segurança ofensiva, tipo OSCP?"
        )
        assert second.reacted, second.diagnosis(
            "certificação não está nos trechos de 'backend em Go': "
            "era para buscar de novo ou oferecer buscar"
        )

    def test_full_domain_change(self, conversation):
        """Salto de tecnologia para saúde: reaproveitar aqui é indefensável."""
        conversation.ask("Quem tem experiência com Kubernetes e Terraform?")

        second = conversation.ask(
            "Mudando de assunto: preciso de uma enfermeira para UTI."
        )
        assert second.searched, second.diagnosis(
            "domínio totalmente novo exige busca nova"
        )
        assert "juliana" in second.answer.lower(), second.diagnosis(
            "esperava chegar em Juliana Matos"
        )

    def test_name_never_searched(self, conversation):
        """Regra 6: nome novo na conversa exige busca, não dedução."""
        conversation.ask("Quem trabalha com frontend React?")

        second = conversation.ask("O que você sabe sobre a Márcia Oliveira?")
        assert second.queried_database, second.diagnosis(
            "nome que não apareceu nas buscas anteriores exige consulta"
        )
        text = second.answer.lower()
        assert "professora" in text or "alfabetiz" in text, second.diagnosis(
            "esperava o perfil real de Márcia Oliveira"
        )

    def test_question_about_candidate_not_retrieved(self, conversation):
        """Afirmar sobre quem não foi recuperado é onde a invenção entra.

        Não adianta perguntar por um detalhe do candidato que veio na busca:
        cada currículo cabe em um chunk, então o texto inteiro dele já está no
        contexto e responder de lá é legítimo. A lacuna de verdade é o
        candidato que a busca não trouxe.
        """
        first = conversation.ask(
            "Quem tem experiência com detecção de fraude em tempo real?"
        )
        assert "larissa" in first.answer.lower(), first.diagnosis(
            "esperava Larissa Moura no primeiro turno"
        )

        second = conversation.ask(
            "E o Bruno Carvalho, ele também trabalha com machine learning?"
        )
        assert second.reacted, second.diagnosis(
            "Bruno Carvalho não veio na busca de fraude: era para consultar "
            "o currículo dele ou oferecer buscar, não deduzir"
        )
        # Bruno é DevOps/SRE, não faz ML. Antes de `find_candidate_by_name`
        # o agente chegava a dizer que ele não estava na base.
        text = second.answer.lower()
        if second.queried_database:
            assert any(
                term in text
                for term in ("devops", "sre", "confiabilidade", "infraestrutura")
            ), second.diagnosis("esperava o perfil real de Bruno Carvalho")

    def test_claim_about_the_whole_set(self, conversation):
        """Regra 3: 'existe algum' exige consultar a base, não o histórico."""
        conversation.ask("Quem sabe Python?")

        second = conversation.ask(
            "Existe algum candidato com experiência em Cobol e mainframe?"
        )
        assert second.queried_database, second.diagnosis(
            "afirmação sobre o conjunto não pode sair do histórico da conversa"
        )

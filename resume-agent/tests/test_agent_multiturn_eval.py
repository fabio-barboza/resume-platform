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

import json
import re
from dataclasses import dataclass, field
from uuid import uuid4

import pytest

from resume_agent.agent import agent
from resume_agent.guardrails.grounding import _fold, person_mentions
from resume_agent.services import document_service

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
            # Trecho longo de propósito: o que reprova costuma estar no meio da
            # resposta (nome citado de passagem, fence de gráfico no fim), e
            # 400 caracteres param no primeiro candidato.
            f"  resposta: {self.answer[:2000]}"
        )


class Conversation:
    """Uma `thread_id` própria: o histórico entre turnos é do checkpointer.

    Mesmo caminho do `chat_service`, que também só envia a mensagem nova e
    deixa o LangGraph carregar o resto do checkpoint.
    """

    def __init__(self):
        self.config = {"configurable": {"thread_id": f"eval-multiturn-{uuid4()}"}}
        self.previous = 0

    def ask(self, question: str) -> Turn:
        result = agent.invoke(
            {"messages": [{"role": "user", "content": question}]}, self.config
        )
        messages = result["messages"]
        # Só o deste turno: ferramenta de turno passado não conta.
        new_messages = messages[self.previous :]
        self.previous = len(messages)

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

    def test_recommendation_must_search_first(self, conversation):
        """Pergunta de recomendação sem nenhuma tool call é invenção pura.

        O caso real: o agente respondeu "realizei uma busca semântica" e listou
        três candidatos com zero tool calls no turno. Nenhum dos três existia.
        """
        turn = conversation.ask(
            "Qual o melhor candidato para uma vaga de Engenheiro de IA aplicada?"
        )
        assert turn.queried_database, turn.diagnosis(
            "recomendação exige consultar a base, não opinar de cabeça"
        )

    def test_claim_about_the_whole_set(self, conversation):
        """Regra 3: 'existe algum' exige consultar a base, não o histórico."""
        conversation.ask("Quem sabe Python?")

        second = conversation.ask(
            "Existe algum candidato com experiência em Cobol e mainframe?"
        )
        assert second.queried_database, second.diagnosis(
            "afirmação sobre o conjunto não pode sair do histórico da conversa"
        )


def _real_name_tokens() -> list[set[str]]:
    """Um conjunto de palavras por nome de candidato que existe na base."""
    return [
        {_fold(word) for word in record["name"].split()}
        for record in document_service.list_inventory()
        if record.get("name")
    ]


def invented_names(answer: str) -> list[str]:
    """Nomes citados na resposta que não batem com nenhum candidato da base.

    O trecho extraído pode arrastar a palavra anterior ("Encontrei Amanda
    Rocha"), então bastam duas palavras em comum com o mesmo cadastro para o
    nome contar como real — sobrenome solto coincidindo não basta.
    """
    real = _real_name_tokens()
    invented = []
    for mention in person_mentions(answer):
        words = {_fold(word) for word in mention.split()}
        if not any(len(words & name) >= 2 for name in real):
            invented.append(mention)
    return invented


class TestNoInventedCandidates:
    """Nome citado tem que existir na base — o erro mais caro do RAG."""

    def test_recommendation_cites_only_real_candidates(self, conversation):
        turn = conversation.ask(
            "Qual o melhor candidato para uma vaga de Engenheiro de IA aplicada?"
        )
        assert not invented_names(turn.answer), turn.diagnosis(
            f"nomes que não existem na base: {invented_names(turn.answer)}"
        )

    def test_follow_up_cites_only_real_candidates(self, conversation):
        """O segundo turno é onde a invenção do primeiro vira racionalização."""
        conversation.ask("Quem tem experiência com dados e machine learning?")

        second = conversation.ask("E quem mais poderia servir para essa vaga?")
        assert not invented_names(second.answer), second.diagnosis(
            f"nomes que não existem na base: {invented_names(second.answer)}"
        )


# Anúncio de vaga real, colado como o usuário cola: texto longo, com seções de
# responsabilidades e diferenciais. É o formato que quebrou na prática — a
# pergunta curta de recomendação ("melhor candidato para IA aplicada", acima)
# não reproduz nem o volume de contexto nem a variedade de termos a buscar.
JOB_POSTING = """Quais os 3 melhores candidatos para essa vaga?

Engenheiro(a) de Inteligência Artificial

Estamos em busca de um(a) Engenheiro(a) de Inteligência Artificial para atuar
em iniciativas de engenharia, experimentação e aceleração de soluções
inovadoras em Inteligência Artificial Generativa (GenAI), agentes de IA e
novas arquiteturas. O profissional terá atuação estratégica no
desenvolvimento e experimentação de soluções de IA, contribuindo para a
criação de protótipos, provas de conceito (PoCs) e pilotos de casos de
negócio. Será responsável por atuar como referência técnica, apoiando
decisões de arquitetura e aplicação de boas práticas de engenharia.

Principais responsabilidades
- Atuar como referência técnica no desenvolvimento de soluções de IA;
- Conduzir a criação de protótipos, PoCs e pilotos usando GenAI e agentes;
- Definir, desenhar e implementar arquiteturas baseadas em IA Generativa;
- Desenvolver aplicações utilizando Python, Java e JavaScript;
- Explorar frameworks de agentes, como Google ADK, CrewAI e similares;
- Aplicar conceitos de Machine Learning, Dados e MLOps;
- Atuar em ambientes Cloud, principalmente Google Cloud Platform (GCP).

Requisitos e conhecimentos
- Experiência em desenvolvimento com Python, Java e/ou JavaScript;
- Experiência com conceitos de Dados, Machine Learning e MLOps;
- Experiência com soluções usando GenAI e agentes de IA;
- Conhecimento em frameworks de orquestração de agentes;
- Experiência com ambientes Cloud, preferencialmente GCP.

Diferenciais
- Experiência com LLMs e aplicações de IA Generativa;
- Conhecimento em engenharia de prompts e integração com APIs de modelos;
- Vivência na construção de agentes autônomos e soluções multiagentes;
- Conhecimento em práticas de engenharia de software aplicadas a IA."""

CHART_REQUEST = "Faça um grafico de pizza mostrando o nivel de aderência de cada um"

# Mesmo recorte que o `markdown.js:extractCharts` da webui faz: só a fence
# ```chart``` vira gráfico na tela. JSON solto ou em ```json``` o usuário vê cru.
CHART_FENCE = re.compile(r"```chart[ \t]*\n(.*?)\n[ \t]*```", re.DOTALL)

# Item de lista que não é uma pessoa: a regra 6 proíbe fechar os 3 pedidos com
# categoria genérica ("3. Candidatos com experiência em Python e Cloud"), que
# foi exatamente como o agente completou a lista quando a busca trouxe menos
# gente do que o pedido.
LIST_ITEM = re.compile(
    r"^\s*(?:\*\*)?([1-3])[.)]\s*(?:\*\*)?\s*(.+?)(?:\*\*)?\s*$", re.MULTILINE
)


def chart_of(answer: str) -> dict | None:
    """O gráfico que a webui desenharia, ou None se não há fence desenhável."""
    match = CHART_FENCE.search(answer)
    if not match:
        return None
    try:
        return json.loads(match.group(1))
    except json.JSONDecodeError:
        return None


def generic_list_items(answer: str) -> list[str]:
    """Itens numerados que não nomeiam uma pessoa da base."""
    real = _real_name_tokens()
    generic = []
    for _, title in LIST_ITEM.findall(answer):
        words = {_fold(word) for word in title.split()}
        if not any(len(words & name) >= 2 for name in real):
            generic.append(title)
    return generic


class TestJobPostingToChart:
    """Vaga colada inteira, depois gráfico de aderência — o fluxo do usuário.

    Os dois turnos falharam juntos em produção: o primeiro completou os três
    lugares com uma categoria genérica no lugar de uma pessoa, e o segundo
    recusou o gráfico de pizza citando as próprias instruções. Nenhum dos dois
    é pego pelos evals de recomendação existentes, que usam pergunta curta e
    param no primeiro turno.
    """

    @pytest.fixture
    def analysed(self, conversation) -> tuple[Conversation, Turn]:
        first = conversation.ask(JOB_POSTING)
        assert first.queried_database, first.diagnosis(
            "vaga colada é pergunta de recomendação: exige consultar a base"
        )
        return conversation, first

    def test_recommendation_cites_only_real_candidates(self, analysed):
        _, first = analysed
        assert not invented_names(first.answer), first.diagnosis(
            f"nomes que não existem na base: {invented_names(first.answer)}"
        )

    def test_list_items_are_people_not_categories(self, analysed):
        """Regra 6: achar menos que 3 é resposta válida; encher a lista não é.

        O caso real: "3. Candidatos com experiência em Python e Cloud", com
        justificativa e sem nome nenhum, para fechar os três pedidos.
        """
        _, first = analysed
        generic = generic_list_items(first.answer)
        assert not generic, first.diagnosis(
            f"item de lista sem pessoa nomeada: {generic}"
        )

    def test_pie_chart_of_adherence_is_drawn(self, analysed):
        """Regra 12: pedido explícito de pizza sai como fence ```chart```.

        A nota de aderência é avaliação do próprio agente sobre os candidatos
        que ele recuperou, não contagem sobre a base — a regra 14 fala de
        número inventado sobre o conjunto, não de comparar quem ele já
        analisou. Recusar aqui é a falha que este eval mede.
        """
        conversation, _ = analysed
        second = conversation.ask(CHART_REQUEST)

        chart = chart_of(second.answer)
        assert chart is not None, second.diagnosis(
            "pedido explícito de gráfico de pizza não virou fence ```chart``` "
            "desenhável pela webui"
        )
        assert chart.get("type") in ("pie", "doughnut"), second.diagnosis(
            f"pediram pizza, veio type={chart.get('type')!r}"
        )
        data = chart.get("data")
        assert isinstance(data, list) and len(data) >= 2, second.diagnosis(
            f"gráfico com menos de duas categorias: {data}"
        )
        assert all(
            isinstance(item, dict) and item.get("value") not in (0, None)
            for item in data
        ), second.diagnosis(f"categoria sem valor útil: {data}")

    def test_refusal_never_cites_the_instructions(self, analysed):
        """Regra 9: o usuário não conhece as regras, então elas não são motivo.

        Vale mesmo quando a recusa for legítima: o texto que apareceu na tela
        foi "As regras de visualização de dados proíbem a criação de gráficos
        com apenas um item ou categoria".
        """
        conversation, _ = analysed
        second = conversation.ask(CHART_REQUEST)

        text = _fold(second.answer)
        leaks = [
            term
            for term in (
                "as regras",
                "as diretrizes",
                "minhas instrucoes",
                "regra 1",
                "count_candidates_by_skill",
            )
            if term in text
        ]
        assert not leaks, second.diagnosis(
            f"resposta expõe as instruções ao usuário: {leaks}"
        )

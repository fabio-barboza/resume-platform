"""Eval da camada de recuperação: o candidato certo aparece no top-k?

Não passa pelo LLM conversacional — mede só `similarity_search`, que é onde
uma recomendação boa se perde antes mesmo do modelo ver o texto. Se este eval
cai, nenhum ajuste de prompt salva a resposta.

Cada pergunta tem um candidato anotado à mão. A busca roda mais larga que a
produção (`K_MEASURED`) para enxergar em que posição o esperado aparece, o que
permite medir recall@1, @3 e no k real.

Rodar:
    pytest -m eval tests/test_retrieval_eval.py -s
"""

import pytest

from resume_agent.db.vector_store import similarity_search
from resume_agent.services import candidate_service

pytestmark = pytest.mark.eval

# k que `find_in_resumes` usa em produção (agent.py).
K_PRODUCTION = 4
# Busca mais larga que a de produção, para enxergar quem ficou logo de fora.
K_MEASURED = 10
# Piso de recall@K_PRODUCTION. Abaixo disso a recuperação regrediu.
MIN_RECALL = 0.80

# Esperado único de propósito: pergunta ambígua mede sorte, não recuperação.
CASES = [
    ("eletricista certificado NR-10 para manutenção de painéis elétricos",
     "Anderson Correia"),
    ("enfermeira com atuação em UTI adulto e pronto-socorro",
     "Juliana Matos"),
    ("gerente de projetos certificado PMP com gestão de portfólio",
     "Thiago Almeida"),
    ("design system de aplicativo usado por dezenas de designers",
     "Bianca Costa"),
    ("desenvolvedor Android com Kotlin e Jetpack Compose",
     "Felipe Nogueira"),
    ("automação de testes end-to-end com Cypress",
     "Renata Souza"),
    ("planejamento tributário, SPED e contabilidade fiscal",
     "Ricardo Teixeira"),
    ("segurança de aplicações com certificações OSCP e CISSP",
     "Amanda Rocha"),
    ("motorista com CNH categoria D e transporte executivo",
     "José Carlos Martins"),
    ("staff engineer de Big Tech com sistemas distribuídos de altíssima escala",
     "Gustavo Pinheiro"),
    ("professora alfabetizadora do ensino fundamental",
     "Márcia Oliveira"),
    ("dashboards em Power BI e pipelines de ETL",
     "Patrícia Lima"),
    ("recrutamento e seleção com folha de pagamento e benefícios",
     "Fernanda Castro"),
    ("vigilante com monitoramento de CFTV em shopping center",
     "Marcos Vieira"),
    ("frontend React com foco em Core Web Vitals e performance",
     "Diego Santana"),
    ("modelo de detecção de fraude em tempo real com machine learning",
     "Larissa Moura"),
    ("arquiteta de soluções cloud certificada em AWS, Azure e GCP",
     "Camila Azevedo"),
    ("secretária executiva com agenda de diretoria e viagens corporativas",
     "Marisa Ferreira"),
    ("auxiliar de cozinha com boas práticas de higiene em restaurante",
     "Maria Aparecida Silva"),
    ("desenvolvedor full stack júnior em início de carreira",
     "Vitor Lopes"),
]

# Uma busca por pergunta: embedding custa chamada de API.
_cache: dict[str, list[str]] = {}


def _ranked_candidates(question: str) -> list[str]:
    """Nomes dos candidatos no top-k, na ordem, sem repetir.

    Chunks distintos do mesmo currículo contam como uma aparição só: o que se
    mede é o candidato recuperado, não o pedaço de texto.
    """
    if question not in _cache:
        docs = similarity_search(question, k=K_MEASURED)
        seen: list[str] = []
        for doc in docs:
            name = doc.metadata.get("candidate_name")
            if name and name not in seen:
                seen.append(name)
        _cache[question] = seen
    return _cache[question]


def _rank_of(question: str, expected: str) -> int | None:
    ranked = _ranked_candidates(question)
    return ranked.index(expected) + 1 if expected in ranked else None


@pytest.mark.parametrize("question,expected", CASES, ids=[e for _, e in CASES])
def test_expected_candidate_in_production_topk(
    populated_database, question: str, expected: str
):
    """O candidato certo entra nos k que o agente realmente enxerga."""
    rank = _rank_of(question, expected)
    assert rank is not None and rank <= K_PRODUCTION, (
        f"{expected!r} não apareceu no top-{K_PRODUCTION} de {question!r}. "
        f"Top-{K_MEASURED}: {_ranked_candidates(question)}"
    )


NAMES = [
    "Rafael Mendes",
    "Márcia Oliveira",
    "Bruno Carvalho",
    "Amanda Rocha",
    "Vitor Lopes",
    "Marisa Ferreira",
]


@pytest.mark.parametrize("name", NAMES)
def test_search_by_proper_name(populated_database, name: str):
    """Nome de pessoa se acha por texto, não por vetor.

    O vizinho mais próximo de "Bruno Carvalho" é outro currículo qualquer, e o
    agente acabava afirmando que a pessoa não estava na base.
    """
    found = candidate_service.search_by_name(name)
    names = [c["name"] for c in found]
    assert names == [name], f"busca por {name!r} devolveu {names}"


@pytest.mark.parametrize(
    "term,expected",
    [
        ("bruno carvalho", "Bruno Carvalho"),  # sem capitalização
        ("marcia", "Márcia Oliveira"),  # sem acento
        ("MENDES", "Rafael Mendes"),  # só sobrenome, caixa alta
        ("carvalho bruno", "Bruno Carvalho"),  # ordem invertida
    ],
)
def test_search_by_name_tolerates_typing(populated_database, term: str, expected: str):
    names = [c["name"] for c in candidate_service.search_by_name(term)]
    assert names == [expected], f"busca por {term!r} devolveu {names}"


def test_nonexistent_name_is_not_invented(populated_database):
    """Não achar aqui é o que autoriza o agente a negar a existência."""
    assert candidate_service.search_by_name("Fulano Inexistente da Silva") == []


@pytest.mark.parametrize("name", NAMES)
def test_search_by_name_returns_the_resume(populated_database, name: str):
    """Achar o candidato sem o texto do currículo não serve para responder."""
    candidate = candidate_service.search_by_name(name)[0]
    text = "".join(c["content"] for c in candidate["chunks"])
    assert text.strip(), f"{name!r} veio sem conteúdo de currículo"


def test_aggregate_recall(populated_database):
    """Recall consolidado, com a tabela por pergunta para inspeção."""
    ranks = [(expected, _rank_of(q, expected)) for q, expected in CASES]
    total = len(ranks)

    def recall(limit: int) -> float:
        hits = sum(1 for _, rank in ranks if rank is not None and rank <= limit)
        return hits / total

    print(f"\nRecall sobre {total} perguntas (k medido = {K_MEASURED}):")
    for expected, rank in ranks:
        mark = "ok " if rank is not None and rank <= K_PRODUCTION else "FORA"
        print(f"  {mark} posição={str(rank or '-'):>2}  {expected}")
    for limit in (1, 3, K_PRODUCTION, K_MEASURED):
        print(f"  recall@{limit}: {recall(limit):.0%}")

    assert recall(K_PRODUCTION) >= MIN_RECALL, (
        f"recall@{K_PRODUCTION} caiu para {recall(K_PRODUCTION):.0%}, "
        f"abaixo do piso de {MIN_RECALL:.0%}"
    )

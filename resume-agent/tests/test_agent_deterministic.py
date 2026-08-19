import pytest

from resume_agent.agent import agent
from resume_agent.services import document_service

pytestmark = pytest.mark.eval


def _ask(question: str) -> str:
    result = agent.invoke({"messages": [{"role": "user", "content": question}]})
    return result["messages"][-1].content


def _check(
    answer: str,
    must_include: list[str] | None = None,
    must_not_include: list[str] | None = None,
) -> list[str]:
    answer_lower = answer.lower()
    failures = []
    for term in must_include or []:
        if term.lower() not in answer_lower:
            failures.append(f"esperava conter {term!r}")
    for term in must_not_include or []:
        if term.lower() in answer_lower:
            failures.append(f"não deveria conter {term!r}")
    return failures


class TestAgentRagEvals:

    def test_search_by_name(self, populated_database):
        answer = _ask("O que você sabe sobre o Rafael Mendes?")
        assert not _check(answer, must_include=["Rafael Mendes"])

    def test_total_inventory(self, populated_database):
        # Cravar o número faz o teste envelhecer no próximo currículo de exemplo.
        total = len(document_service.list_inventory())

        answer = _ask("Quantos currículos existem na base?")
        assert not _check(answer, must_include=[str(total)])

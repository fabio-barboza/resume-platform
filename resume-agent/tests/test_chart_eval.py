"""Eval do bloco ```chart```: pergunta quantitativa gera gráfico com números
medidos, pergunta qualitativa não gera gráfico nenhum.
"""

import json
import re

import pytest

from resume_agent.agent import agent
from resume_agent.services import candidate_service

pytestmark = pytest.mark.eval

_CHART_FENCE = re.compile(r"```chart\s*\n(.*?)\n```", re.DOTALL)
_ALLOWED_TYPES = {"bar", "line", "pie", "doughnut"}


def _ask(question: str) -> str:
    result = agent.invoke({"messages": [{"role": "user", "content": question}]})
    return result["messages"][-1].content


class TestChartEval:
    def test_pergunta_quantitativa_gera_grafico_com_numeros_medidos(
        self, populated_database
    ):
        answer = _ask(
            "Faça um gráfico de candidatos por tecnologia entre Python, Java e "
            "JavaScript"
        )

        match = _CHART_FENCE.search(answer)
        assert match, f"esperava fence ```chart``` na resposta, recebi: {answer!r}"

        chart = json.loads(match.group(1))
        assert chart["type"] in _ALLOWED_TYPES
        assert chart["data"], "gráfico sem categoria"
        for item in chart["data"]:
            assert isinstance(item["label"], str)
            assert isinstance(item["value"], (int, float))

        expected = candidate_service.count_by_skill(["Python", "Java", "JavaScript"])
        for item in chart["data"]:
            assert item["label"] in expected, f"label inesperado: {item['label']!r}"
            assert item["value"] == expected[item["label"]], (
                f"{item['label']}: gráfico tem {item['value']}, "
                f"contagem real é {expected[item['label']]}"
            )

    def test_pergunta_qualitativa_nao_gera_grafico(self, populated_database):
        answer = _ask("Quem tem experiência com React?")

        assert not _CHART_FENCE.search(answer), (
            f"pergunta qualitativa não deveria gerar gráfico, recebi: {answer!r}"
        )

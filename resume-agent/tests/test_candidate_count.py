"""Testes de `count_candidates_by_terms` / `count_by_skill`: contagem literal.

Isolado de `populated_database` — cria candidato/documento/chunk sintéticos
via inserção direta, sem passar por ingestão nem embedding de verdade.
"""

import pytest
from sqlalchemy import insert

from resume_agent.db import repositories as repo
from resume_agent.db.engine import EMBEDDING_DIM, transaction
from resume_agent.db.models import Chunk
from resume_agent.services import candidate_service

_ZERO_VECTOR = [0.0] * EMBEDDING_DIM


def _make_candidate_with_chunks(
    sess, name: str, contents: list[str]
) -> tuple[int, int]:
    candidate_id = repo.insert_candidate(sess, name, None, None)
    document_id = repo.insert_document(
        sess, candidate_id, f"{name}.pdf", f"hash-{name}", 1, "ingested"
    )
    for index, content in enumerate(contents):
        sess.execute(
            insert(Chunk).values(
                id=f"{document_id}-p1-c{index}",
                document_id=document_id,
                page=1,
                chunk_index=index,
                content=content,
                embedding=_ZERO_VECTOR,
            )
        )
    return candidate_id, document_id


@pytest.fixture
def two_candidates(test_database):
    """Ana (JavaScript, dois chunks) e Beto (Python), removidos no fim."""
    with transaction() as sess:
        ana_id, ana_doc_id = _make_candidate_with_chunks(
            sess,
            "Ana",
            [
                "Experiência com JavaScript e React.",
                "Também usou javascript em projetos pessoais.",
            ],
        )
        beto_id, beto_doc_id = _make_candidate_with_chunks(
            sess, "Beto", ["Backend em Python e Django."]
        )
    yield ana_id, beto_id
    with transaction() as sess:
        for candidate_id, document_id in ((ana_id, ana_doc_id), (beto_id, beto_doc_id)):
            repo.delete_document(sess, document_id)
            repo.delete_candidate_if_orphan(sess, candidate_id)


def test_termo_presente_conta_certo(two_candidates):
    """Termo que aparece no currículo de um candidato conta 1."""
    with transaction() as sess:
        counts = repo.count_candidates_by_terms(sess, ["Python"])
    assert counts == {"Python": 1}


def test_termo_ausente_devolve_zero(two_candidates):
    """Termo sem nenhum candidato aparece no resultado com 0, não some."""
    with transaction() as sess:
        counts = repo.count_candidates_by_terms(sess, ["Cobol"])
    assert counts == {"Cobol": 0}


def test_acento_e_caixa_ignorados(two_candidates):
    """'javascript' minúsculo casa com 'JavaScript', sem depender de acento."""
    with transaction() as sess:
        counts = repo.count_candidates_by_terms(sess, ["javascript"])
    assert counts == {"javascript": 1}


def test_dois_chunks_conta_um_candidato(two_candidates):
    """Candidato com termo em dois chunks conta 1 (é o DISTINCT)."""
    with transaction() as sess:
        counts = repo.count_candidates_by_terms(sess, ["JavaScript"])
    assert counts["JavaScript"] == 1


def test_percentual_no_termo_nao_vira_wildcard(two_candidates):
    """'%' no termo é literal, não coringa do LIKE."""
    with transaction() as sess:
        counts = repo.count_candidates_by_terms(sess, ["Python%"])
    assert counts == {"Python%": 0}


def test_lista_vazia_devolve_dict_vazio(two_candidates):
    """Sem termo, nenhuma ida ao banco: devolve {} direto."""
    with transaction() as sess:
        assert repo.count_candidates_by_terms(sess, []) == {}


def test_service_normaliza_termos(two_candidates):
    """`count_by_skill` tira espaço, vazio e duplicado, preservando ordem."""
    counts = candidate_service.count_by_skill([" Python ", "Python", "", "Cobol"])
    assert list(counts.keys()) == ["Python", "Cobol"]
    assert counts == {"Python": 1, "Cobol": 0}


def test_service_lista_vazia_nao_vai_ao_banco():
    """Lista sem termo útil devolve {} sem consultar o banco."""
    assert candidate_service.count_by_skill(["", "   "]) == {}

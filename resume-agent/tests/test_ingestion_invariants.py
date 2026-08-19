"""Testes dos invariantes de negócio da ingestão de currículos.

Diferente dos evals (`test_retrieval_eval.py`, `test_agent_*`), que medem
qualidade de recuperação/resposta contra o banco de exemplo, estes testes
verificam contrato: o que POST/PUT/DELETE de `/resumes` garantem sobre
document_id, chunks e candidato, independente do conteúdo do currículo.

Roda no mesmo banco de teste dos evals (`tests/conftest.py`), mas não usa
`populated_database`: cada teste cria seus próprios documentos com PDFs
sintéticos e os remove no fim, via a fixture `cleanup_documents`.

Extração por LLM e embedding são mockados — nenhum teste depende de rede:
- `Model.get_factual_model` é trocado por algo que estoura exceção, o que
  joga `extract_candidate` no fallback por regex (mesma rede de segurança que
  já existe em produção para quando o LLM falha).
- `Model.get_embedding_model` é trocado por um embedder falso que devolve
  vetor zerado do tamanho certo (1024, dimensão da coluna `chunks.embedding`).
"""


import pytest
from conftest import build_pdf, build_pdf_without_text
from sqlalchemy import func, select

from resume_agent.db import repositories as repo
from resume_agent.db.engine import transaction
from resume_agent.db.models import Chunk
from resume_agent.infra.model import Model
from resume_agent.services import candidate_service, document_service, ingestion_service
from resume_agent.services.errors import ConflictError, InvalidDocumentError

EMBEDDING_DIM = 1024


class _FakeEmbeddings:
    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        return [[0.0] * EMBEDDING_DIM for _ in texts]

    def embed_query(self, text: str) -> list[float]:
        return [0.0] * EMBEDDING_DIM


def _llm_unavailable():
    raise RuntimeError("mock de teste: nenhum teste de invariante chama LLM de verdade")


@pytest.fixture(autouse=True)
def no_network(monkeypatch):
    monkeypatch.setattr(Model, "get_factual_model", _llm_unavailable)
    monkeypatch.setattr(Model, "get_embedding_model", lambda: _FakeEmbeddings())


@pytest.fixture
def cleanup_documents(test_database):
    """Documentos criados no teste; removidos (com candidato órfão) no fim."""
    document_ids: list[int] = []
    yield document_ids
    with transaction() as sess:
        for document_id in document_ids:
            doc = repo.get_document(sess, document_id)
            if doc is None:
                continue
            repo.delete_document(sess, document_id)
            repo.delete_candidate_if_orphan(sess, doc["candidate_id"])


def _chunk_count_in_db(document_id: int) -> int:
    with transaction() as sess:
        stmt = (
            select(func.count())
            .select_from(Chunk)
            .where(Chunk.document_id == document_id)
        )
        return sess.execute(stmt).scalar_one()


def test_posting_same_file_twice_is_noop_without_duplicating(cleanup_documents):
    content = build_pdf("Currículo Fulano de Tal, telefone (11) 91234-5678")

    first = ingestion_service.ingest_resume("cv.pdf", content)
    cleanup_documents.append(first.document_id)
    second = ingestion_service.ingest_resume("cv.pdf", content)

    assert second.duplicate is True
    assert second.document_id == first.document_id
    assert second.candidate_id == first.candidate_id

    documents_with_this_hash = [
        d for d in document_service.list_inventory() if d["id"] == first.document_id
    ]
    assert len(documents_with_this_hash) == 1
    assert _chunk_count_in_db(first.document_id) == first.chunk_count


def test_put_with_different_file_swaps_chunks_keeping_document_id(cleanup_documents):
    original = ingestion_service.ingest_resume(
        "cv.pdf", build_pdf("Conteudo original do curriculo, versao um")
    )
    cleanup_documents.append(original.document_id)

    replaced = ingestion_service.replace_resume(
        original.document_id,
        "cv_v2.pdf",
        build_pdf("Conteudo totalmente novo, versao dois, nada a ver com o primeiro"),
    )

    assert replaced.document_id == original.document_id
    assert replaced.duplicate is False

    with transaction() as sess:
        chunks = repo.list_chunks_by_candidate(sess, replaced.candidate_id)
    texts = " ".join(c["content"] for c in chunks)
    assert "versao dois" in texts
    assert "versao um" not in texts


def test_put_with_same_file_is_noop(cleanup_documents):
    content = build_pdf("Currículo que não vai mudar no PUT")
    original = ingestion_service.ingest_resume("cv.pdf", content)
    cleanup_documents.append(original.document_id)

    repeated = ingestion_service.replace_resume(original.document_id, "cv.pdf", content)

    assert repeated.duplicate is True
    assert repeated.chunk_count == original.chunk_count
    assert _chunk_count_in_db(original.document_id) == original.chunk_count


def test_put_with_another_documents_file_returns_409(cleanup_documents):
    doc_a = ingestion_service.ingest_resume("a.pdf", build_pdf("Currículo do candidato A"))
    cleanup_documents.append(doc_a.document_id)
    doc_b = ingestion_service.ingest_resume("b.pdf", build_pdf("Currículo do candidato B"))
    cleanup_documents.append(doc_b.document_id)

    with pytest.raises(ConflictError):
        ingestion_service.replace_resume(
            doc_a.document_id, "b.pdf", build_pdf("Currículo do candidato B")
        )


def test_put_failing_midway_does_not_leave_document_without_chunks(
    cleanup_documents, monkeypatch
):
    original = ingestion_service.ingest_resume(
        "cv.pdf", build_pdf("Currículo íntegro antes da falha simulada")
    )
    cleanup_documents.append(original.document_id)
    assert original.chunk_count > 0

    def failing_upsert(*args, **kwargs):
        raise RuntimeError("falha simulada no meio da transação")

    monkeypatch.setattr(repo, "upsert_chunks", failing_upsert)

    with pytest.raises(RuntimeError):
        ingestion_service.replace_resume(
            original.document_id, "cv2.pdf", build_pdf("Currículo novo que nunca é gravado")
        )

    # `delete_chunks` rodou na mesma transação que o upsert que falhou: sem
    # rollback, o documento ficaria com zero chunks.
    assert _chunk_count_in_db(original.document_id) == original.chunk_count
    still_there = document_service.get_resume(original.document_id)
    assert still_there["chunk_count"] == original.chunk_count


def test_delete_document_removes_chunks_by_cascade():
    doc = ingestion_service.ingest_resume(
        "cv.pdf", build_pdf("Currículo que vai ser deletado")
    )
    assert _chunk_count_in_db(doc.document_id) > 0

    document_service.delete_resume(doc.document_id)

    assert _chunk_count_in_db(doc.document_id) == 0
    with transaction() as sess:
        assert repo.get_document(sess, doc.document_id) is None


def test_delete_leaving_candidate_without_resume_removes_candidate():
    doc = ingestion_service.ingest_resume(
        "cv.pdf", build_pdf("Currículo único deste candidato")
    )
    candidate_id = doc.candidate_id

    document_service.delete_resume(doc.document_id)

    with transaction() as sess:
        assert repo.get_candidate(sess, candidate_id) is None


def test_two_resumes_with_same_email_link_to_same_candidate(cleanup_documents):
    email = "candidato.duplicado@example.com"
    first = ingestion_service.ingest_resume(
        "cv1.pdf", build_pdf(f"Currículo A, contato {email}")
    )
    cleanup_documents.append(first.document_id)
    second = ingestion_service.ingest_resume(
        "cv2.pdf", build_pdf(f"Currículo B, bem diferente, email {email}")
    )
    cleanup_documents.append(second.document_id)

    assert first.document_id != second.document_id
    assert first.candidate_id == second.candidate_id


def test_candidate_put_does_not_change_chunk_count(cleanup_documents):
    doc = ingestion_service.ingest_resume(
        "cv.pdf", build_pdf("Currículo de quem vai ter o cadastro editado")
    )
    cleanup_documents.append(doc.document_id)
    before = _chunk_count_in_db(doc.document_id)
    assert before > 0

    candidate_service.replace_candidate(
        doc.candidate_id, "Nome Editado à Mão", "editado@example.com", "11999999999"
    )

    assert _chunk_count_in_db(doc.document_id) == before


def test_pdf_without_extractable_text_returns_422_and_stores_nothing():
    from resume_agent.api.errors import _STATUS_BY_ERROR

    total_before, _ = document_service.list_resumes()

    with pytest.raises(InvalidDocumentError):
        ingestion_service.ingest_resume("escaneado.pdf", build_pdf_without_text())

    # `InvalidDocumentError` é o que o router de POST/PUT deixa escapar para o
    # handler registrado em `api/errors.py`; é essa tabela que vira o 422 HTTP.
    assert _STATUS_BY_ERROR[InvalidDocumentError] == 422

    total_after, _ = document_service.list_resumes()
    assert total_after == total_before

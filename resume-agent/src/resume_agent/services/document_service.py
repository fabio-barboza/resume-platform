"""Consulta e remoção de currículos. Sem HTTP, sem Pydantic de request."""

from typing import Any

from resume_agent import storage
from resume_agent.db import repositories as repo
from resume_agent.db.engine import session, transaction
from resume_agent.services.errors import NotFoundError


def list_resumes(limit: int = 100, offset: int = 0) -> tuple[int, list[dict[str, Any]]]:
    """Inventário da base: total de documentos e a página pedida."""
    with session() as sess:
        total = repo.count_documents(sess)
        rows = repo.list_documents(sess, limit, offset)
    return total, rows


def get_resume(document_id: int) -> dict[str, Any]:
    with session() as sess:
        document = repo.get_document(sess, document_id)
    if document is None:
        raise NotFoundError(f"Documento {document_id} não encontrado.")
    return document


def list_inventory() -> list[dict[str, Any]]:
    """Inventário completo, um registro por currículo, com o candidato junto."""
    with session() as sess:
        return repo.list_inventory(sess)


def delete_resume(document_id: int) -> None:
    """Remove o documento e, por cascata, seus chunks.

    Se o candidato ficar sem nenhum currículo, ele também sai: um candidato
    sem documento não representa mais ninguém na base.
    """
    with transaction() as sess:
        document = repo.get_document(sess, document_id)
        if document is None:
            raise NotFoundError(f"Documento {document_id} não encontrado.")
        repo.delete_document(sess, document_id)
        repo.delete_candidate_if_orphan(sess, document["candidate_id"])

    storage.discard(document["filename"], document["file_hash"])

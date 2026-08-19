"""Cadastro do candidato. Não toca em chunks nem em embeddings."""

from typing import Any

from resume_agent import storage
from resume_agent.db import repositories as repo
from resume_agent.db.engine import session, transaction
from resume_agent.db.errors import DuplicateKeyError
from resume_agent.services.errors import ConflictError, NotFoundError


def get_candidate(candidate_id: int) -> dict[str, Any]:
    with session() as sess:
        candidate = repo.get_candidate(sess, candidate_id)
    if candidate is None:
        raise NotFoundError(f"Candidato {candidate_id} não encontrado.")
    return candidate


def get_resume_file(identifier: str) -> tuple[bytes, str]:
    """PDF do currículo mais recente do candidato, achado por ID ou email.

    `identifier` numérico é tratado como ID; qualquer outra coisa, como
    email. Um candidato pode ter mais de um documento vinculado (currículos
    diferentes que casaram pelo mesmo email) — devolve o mais recente.
    """
    with session() as sess:
        candidate = (
            repo.get_candidate(sess, int(identifier))
            if identifier.isdigit()
            else repo.get_candidate_by_email(sess, identifier)
        )
        if candidate is None:
            raise NotFoundError(f"Candidato '{identifier}' não encontrado.")
        document = repo.get_latest_document_by_candidate(sess, candidate["id"])

    if document is None:
        raise NotFoundError(f"Candidato '{identifier}' não tem currículo.")

    content = storage.fetch(document["filename"], document["file_hash"])
    if content is None:
        raise NotFoundError(
            f"Arquivo do currículo de '{identifier}' não está disponível no bucket."
        )
    return content, document["filename"]


def search_by_name(term: str, limit: int = 10) -> list[dict[str, Any]]:
    """Candidatos cujo nome casa com o termo, com o currículo de cada um.

    Busca textual, não semântica: o nome vira filtro em SQL e o conteúdo vem
    por chave estrangeira. É o caminho que não depende de similaridade e por
    isso não produz o falso negativo de "esse candidato não existe".
    """
    with session() as sess:
        found = repo.search_candidates_by_name(sess, term, limit)
        for candidate in found:
            candidate["chunks"] = repo.list_chunks_by_candidate(sess, candidate["id"])
    return found


def replace_candidate(
    candidate_id: int,
    name: str | None,
    email: str | None,
    phone: str | None,
) -> dict[str, Any]:
    """Substituição total do cadastro: campo ausente no body vira NULL.

    Edição manual do cadastro; os currículos e seus vetores ficam intactos.
    """
    try:
        with transaction() as sess:
            updated = repo.replace_candidate(sess, candidate_id, name, email, phone)
            if updated is None:
                raise NotFoundError(f"Candidato {candidate_id} não encontrado.")
    except DuplicateKeyError as exc:
        raise ConflictError(
            f"O email '{email}' já pertence a outro candidato."
        ) from exc
    return updated

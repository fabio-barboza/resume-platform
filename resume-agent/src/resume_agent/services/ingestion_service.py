"""Ingestão de currículos: o que era o script `ingest.py`, agora como serviço.

Nada de HTTP aqui — recebe bytes e nome de arquivo, devolve dados. Dá para
chamar de um router FastAPI, de um teste ou de um script.

Ordem das operações, de propósito:

1. hash e leitura do PDF (barato, falha cedo)
2. deduplicação por `file_hash` — antes de gastar LLM ou embedding
3. extração dos dados do candidato e cálculo dos embeddings, ambos fora da
   transação: são chamadas de rede lentas e não podem segurar lock de tabela
4. transação curta gravando candidato, documento e chunks de uma vez só
"""

import logging
import os
from dataclasses import dataclass

from sqlalchemy.orm import Session

from resume_agent import storage
from resume_agent.db import repositories as repo
from resume_agent.db.engine import session, transaction
from resume_agent.db.errors import DuplicateKeyError
from resume_agent.guardrails.injection import find_injection
from resume_agent.infra import Model
from resume_agent.pdf import (
    Chunk,
    build_chunks,
    file_hash,
    first_pages_text,
    read_pages,
)
from resume_agent.services.errors import (
    ConflictError,
    InvalidDocumentError,
    NotFoundError,
)
from resume_agent.services.extraction_service import (
    EXTRACTION_PAGES,
    CandidateExtraction,
    extract_candidate,
)

logger = logging.getLogger(__name__)

STATUS_INGESTED = "ingested"
STATUS_PENDING_REVIEW = "pending_review"

# Teto de páginas por currículo: cada página vira chunk e embedding, e as
# primeiras ainda alimentam uma chamada de LLM. Sem teto, um PDF de 300 páginas
# passa direto e a conta é do dono da base.
MAX_RESUME_PAGES = int(os.getenv("MAX_RESUME_PAGES", "3"))


@dataclass
class IngestionResult:
    document_id: int
    filename: str
    status: str
    chunk_count: int
    candidate_id: int
    duplicate: bool = False


def _decide_status(extracted: CandidateExtraction) -> str:
    """Sem email identificado o documento entra para revisão manual."""
    return STATUS_INGESTED if extracted.email else STATUS_PENDING_REVIEW


def _prepare(content: bytes, filename: str) -> tuple[list[str], list[Chunk]]:
    """Lê o PDF, valida e fatia.

    Os dois guardrails de ingestão moram aqui, e não no router, porque POST e
    PUT passam pelos dois pelos mesmos motivos — e porque rodam antes da
    extração e do embedding, que são as etapas que custam rede.
    """
    try:
        pages = read_pages(content)
    except Exception as exc:
        raise InvalidDocumentError(
            f"Não foi possível ler o PDF '{filename}': {exc}"
        ) from exc

    if len(pages) > MAX_RESUME_PAGES:
        raise InvalidDocumentError(
            f"O arquivo '{filename}' tem {len(pages)} páginas; o limite é "
            f"{MAX_RESUME_PAGES}. Envie um currículo resumido ou aumente "
            "MAX_RESUME_PAGES."
        )

    chunks = build_chunks(pages, source=filename)
    if not chunks:
        raise InvalidDocumentError(
            f"O arquivo '{filename}' não tem texto extraível "
            "(PDF de imagem escaneada?)."
        )

    # Sobre o texto do PDF inteiro, não só o das páginas que vão para a
    # extração: a instrução plantada costuma ficar longe do cabeçalho.
    injection = find_injection("\n".join(pages))
    if injection is not None:
        logger.warning(
            "Ingestão de '%s' recusada por injeção de prompt (%s).",
            filename,
            injection.pattern,
        )
        raise InvalidDocumentError(
            f"O arquivo '{filename}' foi recusado: o texto contém instrução "
            f"dirigida a um sistema de IA ({injection.pattern}), o que não é "
            "conteúdo de currículo. Trecho extraído do PDF: "
            f"{injection.excerpt!r}. Esse texto pode estar invisível no arquivo "
            "(fonte branca ou tamanho zero), então confie no trecho acima, "
            "não no que aparece na tela ao abrir o PDF."
        )

    return pages, chunks


def _embed(chunks: list[Chunk]) -> list[list[float]]:
    return Model.get_embedding_model().embed_documents([c.content for c in chunks])


def _resolve_candidate(
    sess: Session, extracted: CandidateExtraction, fallback_id: int | None
) -> int:
    """Decide a qual candidato o documento pertence e reescreve o cadastro.

    O email é a chave natural: se já existe candidato com ele, o documento é
    vinculado a esse candidato. A extração é a fonte da verdade — nome, email e
    telefone passam a valer exatamente o que veio do arquivo, inclusive quando
    o valor extraído for nulo.
    """
    existing = (
        repo.get_candidate_by_email(sess, extracted.email) if extracted.email else None
    )

    target_id = existing["id"] if existing else fallback_id
    if target_id is None:
        return repo.insert_candidate(
            sess, extracted.name, extracted.email, extracted.phone
        )

    try:
        repo.replace_candidate(
            sess, target_id, extracted.name, extracted.email, extracted.phone
        )
    except DuplicateKeyError as exc:
        raise ConflictError(
            f"O email '{extracted.email}' já pertence a outro candidato."
        ) from exc
    return target_id


def ingest_resume(filename: str, content: bytes) -> IngestionResult:
    """Ingere um currículo novo (POST /resumes).

    Reenviar um arquivo já ingerido é no-op: devolve o documento existente com
    `duplicate=True`, sem reprocessar embeddings.
    """
    digest = file_hash(content)

    with session() as sess:
        already = repo.get_document_by_hash(sess, digest)
    if already:
        return IngestionResult(
            document_id=already["id"],
            filename=already["filename"],
            status=already["status"],
            chunk_count=already["chunk_count"],
            candidate_id=already["candidate_id"],
            duplicate=True,
        )

    pages, chunks = _prepare(content, filename)
    extracted = extract_candidate(first_pages_text(pages, EXTRACTION_PAGES))
    embeddings = _embed(chunks)
    status = _decide_status(extracted)

    # Grava o arquivo antes da transação: se o commit falhar, o banco nunca
    # fica com documento apontando para um PDF que não existe.
    storage.store(filename, digest, content)
    try:
        with transaction() as sess:
            candidate_id = _resolve_candidate(sess, extracted, fallback_id=None)
            document_id = repo.insert_document(
                sess, candidate_id, filename, digest, len(pages), status
            )
            chunk_count = repo.upsert_chunks(sess, document_id, chunks, embeddings)
    except DuplicateKeyError:
        # Dois uploads simultâneos do mesmo arquivo: o outro venceu a corrida.
        storage.discard(filename, digest)
        with session() as sess:
            already = repo.get_document_by_hash(sess, digest)
        return IngestionResult(
            document_id=already["id"],
            filename=already["filename"],
            status=already["status"],
            chunk_count=already["chunk_count"],
            candidate_id=already["candidate_id"],
            duplicate=True,
        )
    except Exception:
        storage.discard(filename, digest)
        raise

    return IngestionResult(
        document_id=document_id,
        filename=filename,
        status=status,
        chunk_count=chunk_count,
        candidate_id=candidate_id,
    )


def replace_resume(document_id: int, filename: str, content: bytes) -> IngestionResult:
    """Substitui o currículo por inteiro (PUT /resumes/{document_id}).

    Preserva o `document_id`; troca chunks, arquivo e cadastro do candidato.
    Enviar o mesmo arquivo que já está no documento é no-op.
    """
    digest = file_hash(content)

    with session() as sess:
        current = repo.get_document(sess, document_id)
        if current is None:
            raise NotFoundError(f"Documento {document_id} não encontrado.")

        if current["file_hash"] == digest:
            return IngestionResult(
                document_id=document_id,
                filename=current["filename"],
                status=current["status"],
                chunk_count=current["chunk_count"],
                candidate_id=current["candidate_id"],
                duplicate=True,
            )

        clash = repo.get_document_by_hash(sess, digest)
        if clash is not None:
            raise ConflictError(
                f"O arquivo enviado já pertence ao documento {clash['id']}."
            )

    pages, chunks = _prepare(content, filename)
    extracted = extract_candidate(first_pages_text(pages, EXTRACTION_PAGES))
    embeddings = _embed(chunks)
    status = _decide_status(extracted)
    previous_candidate_id = current["candidate_id"]
    previous_hash = current["file_hash"]
    previous_filename = current["filename"]

    # Grava o arquivo novo antes da transação: se o commit falhar, o banco
    # nunca fica com o documento apontando para um PDF que não existe.
    storage.store(filename, digest, content)
    try:
        with transaction() as sess:
            candidate_id = _resolve_candidate(
                sess, extracted, fallback_id=previous_candidate_id
            )
            repo.delete_chunks(sess, document_id)
            repo.update_document(
                sess, document_id, candidate_id, filename, digest, len(pages), status
            )
            chunk_count = repo.upsert_chunks(sess, document_id, chunks, embeddings)
            if candidate_id != previous_candidate_id:
                # Revinculado a outro candidato pelo email: se o antigo ficou
                # sem currículo, não representa mais ninguém.
                repo.delete_candidate_if_orphan(sess, previous_candidate_id)
    except DuplicateKeyError as exc:
        # Corrida: outro upload gravou este `file_hash` entre a checagem de
        # clash acima e o commit.
        storage.discard(filename, digest)
        with session() as sess:
            clash = repo.get_document_by_hash(sess, digest)
        raise ConflictError(
            f"O arquivo enviado já pertence ao documento {clash['id']}."
        ) from exc
    except Exception:
        storage.discard(filename, digest)
        raise

    storage.discard(previous_filename, previous_hash)

    return IngestionResult(
        document_id=document_id,
        filename=filename,
        status=status,
        chunk_count=chunk_count,
        candidate_id=candidate_id,
    )

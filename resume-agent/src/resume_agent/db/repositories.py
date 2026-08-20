"""Acesso às três tabelas via SQLAlchemy. Nenhuma regra de negócio mora aqui.

Toda função recebe a sessão de fora: quem decide o escopo da transação é a
camada de serviço, não o repositório.

As funções devolvem `dict`, não instância de modelo: o serviço, o Pydantic e
o agente consomem linha por chave, e nenhum deles precisa saber que existe um
ORM atrás.
"""

from collections.abc import Iterable
from typing import Any

from sqlalchemy import Row, delete, distinct, exists, func, insert, select, update
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from resume_agent.db.errors import DuplicateKeyError
from resume_agent.db.models import Candidate, Chunk, Document

# `Chunk` aqui é a linha da tabela; o do `pdf` é o pedaço de texto recém
# fatiado, que ainda não tem ID nem embedding. São coisas diferentes.
from resume_agent.pdf import Chunk as PdfChunk

UNIQUE_VIOLATION = "23505"


def _row_to_dict(row: Row[Any] | None) -> dict[str, Any] | None:
    return dict(row._mapping) if row is not None else None


def _rows_to_dicts(rows: Iterable[Row[Any]]) -> list[dict[str, Any]]:
    return [dict(row._mapping) for row in rows]


# --- candidates -------------------------------------------------------------

_CANDIDATE_COLUMNS = (
    Candidate.id,
    Candidate.name,
    Candidate.email,
    Candidate.phone,
    Candidate.created_at,
)


def insert_candidate(
    session: Session, name: str | None, email: str | None, phone: str | None
) -> int:
    stmt = (
        insert(Candidate)
        .values(name=name, email=email, phone=phone)
        .returning(Candidate.id)
    )
    return session.execute(stmt).scalar_one()


def get_candidate(session: Session, candidate_id: int) -> dict[str, Any] | None:
    stmt = select(*_CANDIDATE_COLUMNS).where(Candidate.id == candidate_id)
    return _row_to_dict(session.execute(stmt).first())


def get_candidate_by_email(session: Session, email: str) -> dict[str, Any] | None:
    """Busca case-insensitive: o email é a chave natural do candidato."""
    stmt = select(*_CANDIDATE_COLUMNS).where(
        func.lower(Candidate.email) == func.lower(email)
    )
    return _row_to_dict(session.execute(stmt).first())


def search_candidates_by_name(
    session: Session, term: str, limit: int = 10
) -> list[dict[str, Any]]:
    """Candidatos cujo nome casa com o termo, por texto e não por vetor.

    Cada palavra do termo precisa aparecer no nome, em qualquer ordem: "mendes
    rafael" encontra "Rafael Mendes". `unaccent` de ambos os lados faz "Marcia"
    casar com "Márcia" — quem digita o nome raramente digita o acento.

    Varredura sequencial, sem índice: a tabela de candidatos é pequena por
    natureza e o custo só passaria a importar em outra ordem de grandeza.
    """
    words = [p for p in term.split() if p]
    if not words:
        return []

    normalized_name = func.unaccent(func.lower(Candidate.name))
    stmt = select(*_CANDIDATE_COLUMNS).where(Candidate.name.is_not(None))
    for word in words:
        # Escapa `%` e `_` do termo: sem isso, o que o usuário digita vira
        # wildcard do LIKE.
        escaped = word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        target = func.unaccent(func.lower(escaped))
        stmt = stmt.where(normalized_name.like("%" + target + "%", escape="\\"))

    return _rows_to_dicts(session.execute(stmt.order_by(Candidate.name).limit(limit)))


def replace_candidate(
    session: Session,
    candidate_id: int,
    name: str | None,
    email: str | None,
    phone: str | None,
) -> dict[str, Any] | None:
    """Substituição total: campo ausente vira NULL, não preserva valor anterior."""
    stmt = (
        update(Candidate)
        .where(Candidate.id == candidate_id)
        .values(name=name, email=email, phone=phone)
        .returning(*_CANDIDATE_COLUMNS)
    )
    try:
        return _row_to_dict(session.execute(stmt).first())
    except IntegrityError as exc:
        # Só a colisão de email vira erro de domínio; qualquer outra violação
        # de integridade continua subindo como está.
        if getattr(exc.orig, "sqlstate", None) == UNIQUE_VIOLATION:
            raise DuplicateKeyError(str(exc)) from exc
        raise


def delete_candidate_if_orphan(session: Session, candidate_id: int) -> bool:
    """Remove o candidato se ele não tiver mais nenhum documento vinculado."""
    sem_documento = ~exists(
        select(1).where(Document.candidate_id == Candidate.id).correlate(Candidate)
    )
    stmt = (
        delete(Candidate)
        .where(Candidate.id == candidate_id, sem_documento)
        .returning(Candidate.id)
    )
    return session.execute(stmt).first() is not None


# --- documents --------------------------------------------------------------

# Contagem de chunks como subquery correlata, não como JOIN + GROUP BY: assim
# documento sem nenhum chunk continua aparecendo na lista, com zero.
_CHUNK_COUNT = (
    select(func.count())
    .select_from(Chunk)
    .where(Chunk.document_id == Document.id)
    .correlate(Document)
    .scalar_subquery()
    .label("chunk_count")
)


def _document_select():
    """Documento + candidato + contagem de chunks, o formato que a API espera."""
    return select(
        Document.id,
        Document.candidate_id,
        Document.filename,
        Document.file_hash,
        Document.pages,
        Document.status,
        Document.ingested_at,
        Candidate.name.label("candidate_name"),
        Candidate.email.label("candidate_email"),
        Candidate.phone.label("candidate_phone"),
        Candidate.created_at.label("candidate_created_at"),
        _CHUNK_COUNT,
    ).join(Candidate, Candidate.id == Document.candidate_id)


def insert_document(
    session: Session,
    candidate_id: int,
    filename: str,
    file_hash: str,
    pages: int,
    status: str,
) -> int:
    stmt = (
        insert(Document)
        .values(
            candidate_id=candidate_id,
            filename=filename,
            file_hash=file_hash,
            pages=pages,
            status=status,
        )
        .returning(Document.id)
    )
    try:
        return session.execute(stmt).scalar_one()
    except IntegrityError as exc:
        # Colisão de `file_hash`: dois uploads simultâneos do mesmo arquivo
        # furando o dedupe feito fora da transação. Vira erro de domínio;
        # qualquer outra violação de integridade continua subindo como está.
        if getattr(exc.orig, "sqlstate", None) == UNIQUE_VIOLATION:
            raise DuplicateKeyError(str(exc)) from exc
        raise


def get_document(session: Session, document_id: int) -> dict[str, Any] | None:
    stmt = _document_select().where(Document.id == document_id)
    return _row_to_dict(session.execute(stmt).first())


def get_document_by_hash(session: Session, file_hash: str) -> dict[str, Any] | None:
    stmt = _document_select().where(Document.file_hash == file_hash)
    return _row_to_dict(session.execute(stmt).first())


def get_latest_document_by_candidate(
    session: Session, candidate_id: int
) -> dict[str, Any] | None:
    """O currículo mais recente do candidato — ele pode ter mais de um."""
    stmt = (
        select(Document.filename, Document.file_hash)
        .where(Document.candidate_id == candidate_id)
        .order_by(Document.ingested_at.desc())
        .limit(1)
    )
    return _row_to_dict(session.execute(stmt).first())


def list_documents(session: Session, limit: int, offset: int) -> list[dict[str, Any]]:
    stmt = _document_select().order_by(Document.filename).limit(limit).offset(offset)
    return _rows_to_dicts(session.execute(stmt))


def count_documents(session: Session) -> int:
    return session.execute(select(func.count()).select_from(Document)).scalar_one()


def list_inventory(session: Session) -> list[dict[str, Any]]:
    """Inventário completo: um registro por documento, com o candidato junto.

    É o que a tool `list_resumes` do agente consome. Uma linha por documento —
    contar aqui é contar currículo, não nome de arquivo, que desde que a
    ingestão virou upload deixou de ser único.
    """
    stmt = (
        select(
            Document.id,
            Document.filename,
            Document.status,
            Candidate.id.label("candidate_id"),
            Candidate.name,
            Candidate.email,
            Candidate.phone,
        )
        .join(Candidate, Candidate.id == Document.candidate_id)
        .order_by(func.coalesce(Candidate.name, Document.filename), Document.id)
    )
    return _rows_to_dicts(session.execute(stmt))


def update_document(
    session: Session,
    document_id: int,
    candidate_id: int,
    filename: str,
    file_hash: str,
    pages: int,
    status: str,
) -> None:
    stmt = (
        update(Document)
        .where(Document.id == document_id)
        .values(
            candidate_id=candidate_id,
            filename=filename,
            file_hash=file_hash,
            pages=pages,
            status=status,
            # Relógio do banco, não o do processo da aplicação.
            ingested_at=func.now(),
        )
    )
    try:
        session.execute(stmt)
    except IntegrityError as exc:
        # Mesma colisão de `file_hash` do `insert_document`, aqui na
        # substituição: a checagem de clash roda fora da transação.
        if getattr(exc.orig, "sqlstate", None) == UNIQUE_VIOLATION:
            raise DuplicateKeyError(str(exc)) from exc
        raise


def delete_document(session: Session, document_id: int) -> bool:
    """Remove o documento. Os chunks caem junto pelo ON DELETE CASCADE.

    DELETE de Core, não `session.delete()`: o ORM carregaria os chunks para
    apagar um a um em Python e o cascade do banco deixaria de ser exercido.
    """
    stmt = delete(Document).where(Document.id == document_id).returning(Document.id)
    return session.execute(stmt).first() is not None


# --- chunks -----------------------------------------------------------------


def list_chunks_by_candidate(
    session: Session, candidate_id: int
) -> list[dict[str, Any]]:
    """Todo o texto dos currículos de um candidato, na ordem de leitura.

    Complemento da busca por nome: achado o candidato pelo texto, o conteúdo
    vem por chave estrangeira, sem passar por similaridade.
    """
    stmt = (
        select(
            Chunk.content,
            Chunk.page,
            Chunk.chunk_index,
            Document.id.label("document_id"),
            Document.filename,
        )
        .join(Document, Document.id == Chunk.document_id)
        .where(Document.candidate_id == candidate_id)
        .order_by(Document.id, Chunk.page, Chunk.chunk_index)
    )
    return _rows_to_dicts(session.execute(stmt))


def count_candidates_by_terms(session: Session, terms: list[str]) -> dict[str, int]:
    """Quantos candidatos distintos citam cada termo, um por currículo.

    Contagem literal (substring, sem acento, sem caixa) via ILIKE, não busca
    semântica: existe para dar número exato onde `find_in_resumes` só traria
    os vizinhos mais próximos. Um único round-trip — cada termo vira uma
    subquery escalar de `COUNT(DISTINCT documents.candidate_id)`, todas na
    mesma SELECT, em vez de uma consulta por termo.
    """
    if not terms:
        return {}

    normalized_content = func.unaccent(func.lower(Chunk.content))
    columns = []
    for index, term in enumerate(terms):
        # Escapa `%`, `_` e `\`: sem isso, o termo do usuário vira wildcard do LIKE.
        escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        target = func.unaccent(func.lower(escaped))
        condition = normalized_content.like("%" + target + "%", escape="\\")
        columns.append(
            select(func.count(distinct(Document.candidate_id)))
            .select_from(Chunk)
            .join(Document, Document.id == Chunk.document_id)
            .where(condition)
            .scalar_subquery()
            .label(f"term_{index}")
        )

    row = session.execute(select(*columns)).one()
    return dict(zip(terms, row._mapping.values(), strict=True))


def delete_chunks(session: Session, document_id: int) -> None:
    session.execute(delete(Chunk).where(Chunk.document_id == document_id))


def upsert_chunks(
    session: Session,
    document_id: int,
    chunks: Iterable[PdfChunk],
    embeddings: Iterable[list[float]],
) -> int:
    """Grava os chunks. Reprocessar o mesmo arquivo atualiza em vez de duplicar."""
    rows = [
        {
            "id": chunk.id_for(document_id),
            "document_id": document_id,
            "page": chunk.page,
            "chunk_index": chunk.chunk_index,
            "content": chunk.content,
            "embedding": embedding,
        }
        for chunk, embedding in zip(chunks, embeddings, strict=True)
    ]
    if not rows:
        return 0

    stmt = pg_insert(Chunk)
    stmt = stmt.on_conflict_do_update(
        index_elements=[Chunk.id],
        set_={
            "content": stmt.excluded.content,
            "embedding": stmt.excluded.embedding,
            "page": stmt.excluded.page,
            "chunk_index": stmt.excluded.chunk_index,
        },
    )
    session.execute(stmt, rows)
    return len(rows)

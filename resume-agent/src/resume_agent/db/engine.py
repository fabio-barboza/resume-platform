"""Engine do SQLAlchemy e helpers de sessão.

A engine é criada sob demanda (primeiro uso) e reaproveitada pelo processo
inteiro — API e agente rodam juntos e compartilham o mesmo pool de conexões,
que agora é o `QueuePool` do próprio SQLAlchemy.

`session()` entrega uma sessão de leitura: nunca dá commit, dá rollback ao
sair para não deixar transação pendurada no pool.
`transaction()` dá commit no fim e rollback em exceção: tudo que grava
metadado e vetor junto passa por aqui, para que uma falha no meio não deixe
documento sem chunk.
"""

import os
from collections.abc import Iterator
from contextlib import contextmanager

from dotenv import load_dotenv
from sqlalchemy import Engine, create_engine
from sqlalchemy.orm import Session, sessionmaker

load_dotenv()

# Dimensão do vetor gerado por EMBEDDING_MODEL. Precisa bater com a coluna
# `chunks.embedding` criada na migração: mudar o modelo de embedding exige
# nova migração, não só trocar a variável.
EMBEDDING_DIM = int(os.getenv("EMBEDDING_DIM", "1024"))


def database_url() -> str:
    """URL de conexão. DATABASE_URL vence; senão monta a partir de POSTGRES_*."""
    url = os.getenv("DATABASE_URL")
    if url:
        return url
    user = os.getenv("POSTGRES_USER", "resume_agent")
    password = os.getenv("POSTGRES_PASSWORD", "resume_agent")
    host = os.getenv("POSTGRES_HOST", "localhost")
    port = os.getenv("POSTGRES_PORT", "5432")
    db = os.getenv("POSTGRES_DB", "resume_agent")
    return f"postgresql://{user}:{password}@{host}:{port}/{db}"


def sqlalchemy_url() -> str:
    """A mesma URL, com o driver explícito.

    O projeto usa psycopg3; sem o prefixo o SQLAlchemy tentaria o psycopg2,
    que não é dependência daqui.
    """
    url = database_url()
    if url.startswith("postgresql://"):
        return url.replace("postgresql://", "postgresql+psycopg://", 1)
    return url


_engine: Engine | None = None
_session_factory: sessionmaker[Session] | None = None


def get_engine() -> Engine:
    global _engine
    if _engine is None:
        min_size = int(os.getenv("DB_POOL_MIN_SIZE", "1"))
        max_size = int(os.getenv("DB_POOL_MAX_SIZE", "10"))
        _engine = create_engine(
            sqlalchemy_url(),
            pool_size=min_size,
            max_overflow=max(max_size - min_size, 0),
        )
    return _engine


def get_session_factory() -> sessionmaker[Session]:
    global _session_factory
    if _session_factory is None:
        _session_factory = sessionmaker(bind=get_engine())
    return _session_factory


def dispose_engine() -> None:
    global _engine, _session_factory
    if _engine is not None:
        _engine.dispose()
        _engine = None
        _session_factory = None


@contextmanager
def session() -> Iterator[Session]:
    """Sessão de leitura: sem commit, rollback ao sair."""
    with get_session_factory()() as sess:
        yield sess
        sess.rollback()


@contextmanager
def transaction() -> Iterator[Session]:
    """Sessão dentro de uma transação: commit no fim, rollback em exceção."""
    with get_session_factory()() as sess:
        try:
            yield sess
            sess.commit()
        except Exception:
            sess.rollback()
            raise

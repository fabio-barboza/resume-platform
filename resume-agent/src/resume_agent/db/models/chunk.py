"""Tabela `chunks`: um pedaço do currículo com seu embedding.

É a única tabela que o agente lê na busca semântica.
"""

from pgvector.sqlalchemy import Vector
from sqlalchemy import (
    BigInteger,
    ForeignKey,
    Index,
    Integer,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column

from resume_agent.db.engine import EMBEDDING_DIM
from resume_agent.db.models.base import Base


class Chunk(Base):
    __tablename__ = "chunks"
    __table_args__ = (
        UniqueConstraint("document_id", "page", "chunk_index"),
        # HNSW com distância de cosseno: é a métrica que `similarity_search`
        # usa (`embedding <=> query`). Trocar o operador aqui invalida o índice
        # para aquela busca.
        Index(
            "chunks_embedding_hnsw_idx",
            "embedding",
            postgresql_using="hnsw",
            postgresql_ops={"embedding": "vector_cosine_ops"},
        ),
    )

    # ID determinístico montado pela aplicação (`pdf.Chunk.id_for`), não
    # sequência: reprocessar o mesmo PDF precisa gerar as mesmas chaves para o
    # upsert atualizar em vez de duplicar.
    id: Mapped[str] = mapped_column(Text, primary_key=True)
    document_id: Mapped[int] = mapped_column(
        BigInteger,
        ForeignKey("documents.id", ondelete="CASCADE"),
        nullable=False,
    )
    page: Mapped[int] = mapped_column(Integer, nullable=False)
    chunk_index: Mapped[int] = mapped_column(Integer, nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    embedding: Mapped[list[float]] = mapped_column(
        Vector(EMBEDDING_DIM), nullable=False
    )

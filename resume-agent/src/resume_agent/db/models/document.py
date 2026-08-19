"""Tabela `documents`: um currículo enviado, sempre ligado a um candidato."""

from datetime import datetime

from sqlalchemy import (
    BigInteger,
    DateTime,
    ForeignKey,
    Identity,
    Index,
    Integer,
    Text,
    func,
)
from sqlalchemy.orm import Mapped, mapped_column

from resume_agent.db.models.base import Base


class Document(Base):
    __tablename__ = "documents"
    __table_args__ = (Index("documents_candidate_id_idx", "candidate_id"),)

    id: Mapped[int] = mapped_column(
        BigInteger, Identity(always=True), primary_key=True
    )
    candidate_id: Mapped[int] = mapped_column(
        BigInteger,
        # O cascade é do banco: apagar candidato leva os documentos junto sem
        # o ORM precisar carregar nada.
        ForeignKey("candidates.id", ondelete="CASCADE"),
        nullable=False,
    )
    filename: Mapped[str] = mapped_column(Text, nullable=False)
    file_hash: Mapped[str] = mapped_column(Text, nullable=False, unique=True)
    pages: Mapped[int] = mapped_column(Integer, nullable=False)
    status: Mapped[str] = mapped_column(Text, nullable=False)
    ingested_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )

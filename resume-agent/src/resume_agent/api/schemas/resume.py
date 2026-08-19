"""Schemas de currículo — `routers/resumes.py`.

Dois grupos: leitura (`ResumeSummary`, `ResumeListResponse`, do GET) e
ingestão (`ResumeIngestionResult`, `ResumeIngestionResponse`, do POST e PUT).
"""

from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field

from resume_agent.api.schemas.candidate import CandidateSummary


class ResumeSummary(BaseModel):
    """Documento e o candidato vinculado a ele."""

    document_id: int
    filename: str
    file_hash: str
    pages: int
    status: str
    ingested_at: datetime
    chunk_count: int
    candidate: CandidateSummary

    @classmethod
    def from_row(cls, row: dict[str, Any]) -> "ResumeSummary":
        return cls(
            document_id=row["id"],
            filename=row["filename"],
            file_hash=row["file_hash"],
            pages=row["pages"],
            status=row["status"],
            ingested_at=row["ingested_at"],
            chunk_count=row["chunk_count"],
            candidate=CandidateSummary(
                id=row["candidate_id"],
                name=row["candidate_name"],
                email=row["candidate_email"],
                phone=row["candidate_phone"],
                created_at=row["candidate_created_at"],
            ),
        )


class ResumeListResponse(BaseModel):
    """Inventário da base."""

    total: int = Field(description="Total de currículos indexados.")
    items: list[ResumeSummary]


class ResumeIngestionResult(BaseModel):
    """Resultado de um arquivo do upload. Falha em um não derruba os demais."""

    filename: str
    document_id: int | None = Field(
        default=None, description="null quando o arquivo falhou."
    )
    candidate_id: int | None = None
    status: str = Field(
        description=(
            "`ingested`, `pending_review` (nenhum email identificado) ou "
            "`failed`."
        )
    )
    chunk_count: int = 0
    duplicate: bool = Field(
        default=False,
        description="true quando o arquivo já estava na base e nada foi reprocessado.",
    )
    error: str | None = Field(
        default=None, description="Motivo da falha, quando status = `failed`."
    )


class ResumeIngestionResponse(BaseModel):
    """Um resultado por arquivo enviado, na ordem do upload."""

    results: list[ResumeIngestionResult]

"""Schemas do cadastro do candidato — `routers/candidates.py`."""

from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class CandidateSummary(BaseModel):
    """Cadastro do candidato, como está gravado hoje."""

    id: int
    name: str | None = None
    email: str | None = None
    phone: str | None = None
    created_at: datetime

    @classmethod
    def from_row(cls, row: dict[str, Any]) -> "CandidateSummary":
        return cls(
            id=row["id"],
            name=row["name"],
            email=row["email"],
            phone=row["phone"],
            created_at=row["created_at"],
        )


class CandidateReplaceRequest(BaseModel):
    """Substituição total do cadastro. Campo ausente vira null — não é PATCH."""

    model_config = ConfigDict(
        json_schema_extra={
            "example": {
                "name": "Rafael Mendes",
                "email": "rafael.mendes@example.com",
                "phone": "(11) 98888-7777",
            }
        }
    )

    name: str | None = Field(default=None, description="Nome; ausente = null.")
    email: str | None = Field(
        default=None, description="Email; chave natural do candidato. Ausente = null."
    )
    phone: str | None = Field(default=None, description="Telefone; ausente = null.")

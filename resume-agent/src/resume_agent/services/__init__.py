"""Camada de serviço: toda a regra de negócio, chamável fora do FastAPI."""

from resume_agent.services.errors import (
    ConflictError,
    DomainError,
    InvalidDocumentError,
    NotFoundError,
)

__all__ = [
    "ConflictError",
    "DomainError",
    "InvalidDocumentError",
    "NotFoundError",
]

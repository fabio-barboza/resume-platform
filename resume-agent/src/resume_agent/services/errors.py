"""Erros de domínio.

Ficam na camada de serviço, sem nada de HTTP: quem traduz para status code é
`resume_agent.api.errors`. Assim o serviço continua chamável fora do FastAPI.
"""


class DomainError(Exception):
    """Base de tudo que o domínio recusa."""


class NotFoundError(DomainError):
    """Recurso inexistente."""


class ConflictError(DomainError):
    """A operação violaria uma chave natural (file_hash, email)."""


class InvalidDocumentError(DomainError):
    """Arquivo ilegível ou sem texto extraível."""

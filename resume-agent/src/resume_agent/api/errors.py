"""Tradução de erro de domínio para status HTTP.

Fica só aqui: os serviços levantam exceções de domínio e não sabem o que é um
status code.
"""

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from resume_agent.services.errors import (
    ConflictError,
    InvalidDocumentError,
    NotFoundError,
)

_STATUS_BY_ERROR = {
    NotFoundError: 404,
    ConflictError: 409,
    InvalidDocumentError: 422,
}


def register_exception_handlers(app: FastAPI) -> None:
    for error_type, status_code in _STATUS_BY_ERROR.items():

        async def handler(
            _: Request, exc: Exception, status_code: int = status_code
        ) -> JSONResponse:
            return JSONResponse(status_code=status_code, content={"detail": str(exc)})

        app.add_exception_handler(error_type, handler)

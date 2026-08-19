"""Aplicação FastAPI: superfície HTTP de manutenção da base de currículos.

A regra de negócio toda mora em `resume_agent.services`. Aqui só existe
montagem: routers, tradução de erro e ciclo de vida do pool.
"""

import os
import threading
from contextlib import asynccontextmanager
from typing import Any

import uvicorn
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.openapi.utils import get_openapi
from sqlalchemy import select

from resume_agent.api.errors import register_exception_handlers
from resume_agent.api.routers import candidates, chat, resumes
from resume_agent.config import API_HOST, API_PORT
from resume_agent.db.engine import dispose_engine, get_engine, session

DESCRIPTION = """
Manutenção da base de currículos consultada pelo agente RAG.

O currículo enviado é a **fonte da verdade** do cadastro: a cada ingestão,
nome, email e telefone do candidato são extraídos do arquivo e substituem por
inteiro o que estava gravado — inclusive quando o valor extraído for nulo.
O email é a chave natural que vincula currículos ao mesmo candidato.

Não há autenticação: qualquer chamada pode alterar qualquer currículo.
"""


@asynccontextmanager
async def lifespan(_: FastAPI):
    get_engine()
    yield
    dispose_engine()


app = FastAPI(
    title="resume-agent API",
    description=DESCRIPTION,
    version="0.1.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

register_exception_handlers(app)
app.include_router(resumes.router)
app.include_router(candidates.router)
app.include_router(chat.router)


def _mark_binary_fields(node: Any) -> Any:
    """Anota `format: binary` nos campos de upload do schema.

    O FastAPI emite OpenAPI 3.1, onde arquivo é `contentMediaType:
    application/octet-stream`. O Swagger UI só desenha o seletor de arquivo
    quando encontra o `format: binary` do 3.0 — sem isso ele renderiza um
    campo de texto com um exemplo binário ilegível. Manter os dois deixa o
    documento válido no 3.1 e o "Try it out" utilizável.
    """
    if isinstance(node, dict):
        if node.get("contentMediaType") == "application/octet-stream":
            node.setdefault("format", "binary")
        for value in node.values():
            _mark_binary_fields(value)
    elif isinstance(node, list):
        for item in node:
            _mark_binary_fields(item)
    return node


def custom_openapi() -> dict[str, Any]:
    if not app.openapi_schema:
        app.openapi_schema = _mark_binary_fields(get_openapi(
            title=app.title,
            version=app.version,
            description=app.description,
            routes=app.routes,
        ))
    return app.openapi_schema


app.openapi = custom_openapi


@app.get("/health", tags=["Infra"], summary="Liveness da API e do banco")
def health() -> dict[str, str]:
    with session() as sess:
        sess.execute(select(1))
    return {"status": "ok"}


def serve_in_background() -> threading.Thread:
    """Sobe o uvicorn numa thread daemon, ao lado do REPL do agente.

    Mesmo processo de propósito: API e agente compartilham o pool, então um
    currículo ingerido pelo Swagger já aparece na próxima busca do agente.
    """
    server = uvicorn.Server(
        uvicorn.Config(
            app,
            host=API_HOST,
            port=API_PORT,
            log_level=os.getenv("API_LOG_LEVEL", "warning"),
        )
    )
    thread = threading.Thread(target=server.run, name="resume-agent-api", daemon=True)
    thread.start()
    return thread

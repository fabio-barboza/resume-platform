"""Configuração da API lida do ambiente.

Separado de `api.main` de propósito: o agente precisa saber em que endereço a
API está rodando para orientar o usuário, e importar `api.main` só para isso
traria junto a construção do app FastAPI.
"""

import os

from dotenv import load_dotenv

load_dotenv()

API_HOST = os.getenv("API_HOST", "127.0.0.1")
API_PORT = int(os.getenv("API_PORT", "8000"))


def api_url() -> str:
    """Endereço em que a API responde, do ponto de vista de quem usa."""
    host = "localhost" if API_HOST in ("0.0.0.0", "127.0.0.1") else API_HOST
    return f"http://{host}:{API_PORT}"


def swagger_url() -> str:
    return f"{api_url()}/docs"

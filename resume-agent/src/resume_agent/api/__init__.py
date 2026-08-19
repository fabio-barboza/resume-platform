"""Camada HTTP: FastAPI + Swagger sobre a camada de serviço."""

from resume_agent.api.main import app, serve_in_background

__all__ = ["app", "serve_in_background"]

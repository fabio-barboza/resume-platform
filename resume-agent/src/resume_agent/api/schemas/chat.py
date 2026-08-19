"""Schemas de chat — `routers/chat.py`."""

from pydantic import BaseModel, Field


class ChatRequest(BaseModel):
    session_id: str = Field(description="Identificador da conversa, gerado pelo cliente.")
    message: str = Field(description="Pergunta do usuário.")


class ChatResponse(BaseModel):
    content: str = Field(description="Resposta do agente em markdown.")

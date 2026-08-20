"""Schemas de chat — `routers/chat.py`."""

from pydantic import BaseModel, Field


class ChatRequest(BaseModel):
    session_id: str = Field(
        description="Identificador da conversa, gerado pelo cliente."
    )
    message: str = Field(description="Pergunta do usuário.")


class ChatResponse(BaseModel):
    content: str = Field(description="Resposta do agente em markdown.")


# Os quatro modelos abaixo documentam o contrato de `POST /chat/stream` para o
# Swagger. Não são usados como `response_model` — `StreamingResponse` não
# suporta isso — mas fixam o formato de cada evento em código, não só em
# markdown.


class ChatStreamStart(BaseModel):
    """Primeiro evento do stream, sempre emitido."""

    session_id: str = Field(description="Identificador da conversa.")


class ChatStreamToken(BaseModel):
    """Pedaço do texto da resposta final (`event: token`)."""

    text: str = Field(
        description="Delta de texto para acrescentar à resposta em renderização."
    )


class ChatStreamTool(BaseModel):
    """Início ou fim de uma chamada de ferramenta (`event: tool`)."""

    name: str = Field(description="Nome da ferramenta chamada pelo agente.")
    status: str = Field(
        description="`start` quando a ferramenta é chamada, `end` quando termina."
    )


class ChatStreamDone(BaseModel):
    """Fim normal do turno (`event: done`). Nunca acompanha `error`."""

    content: str = Field(
        description="Resposta completa em markdown, canônica: prevalece sobre a concatenação dos tokens."
    )


class ChatStreamError(BaseModel):
    """Falha no turno (`event: error`). Nenhum `done` é emitido depois."""

    detail: str = Field(
        description="Mensagem de erro em português, sem detalhe interno da exceção."
    )

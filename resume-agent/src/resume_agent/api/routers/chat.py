"""Endpoint de chat com o agente. Router fino: histórico e stream ficam no service.

Uma conversa por `session_id`, gerado pelo cliente (webui) e perdida ao
reiniciar o processo — mesmo comportamento efêmero do REPL em `__main__.py`.
"""

import json

from fastapi import APIRouter
from fastapi.responses import StreamingResponse

from resume_agent.api.schemas.chat import ChatRequest, ChatResponse
from resume_agent.services import chat_service

router = APIRouter(prefix="/chat", tags=["Chat"])


@router.post(
    "",
    response_model=ChatResponse,
    summary="Conversar com o agente de currículos",
    description=(
        "Mesmo agente do REPL, exposto por HTTP. O histórico da conversa é "
        "mantido em memória por `session_id` e não sobrevive a um restart."
    ),
)
def chat(payload: ChatRequest) -> ChatResponse:
    content = chat_service.ask(payload.session_id, payload.message)
    return ChatResponse(content=content)


@router.post(
    "/stream",
    summary="Conversar com o agente com resposta em streaming (SSE)",
    description=(
        "Mesmo agente de `POST /chat`, em `text/event-stream`. Cada frame é "
        "`event: <tipo>\\ndata: <json>\\n\\n`. Tipos: `start` "
        "(`ChatStreamStart`, sempre primeiro), `tool` (`ChatStreamTool`, "
        "início/fim de chamada de ferramenta), `token` (`ChatStreamToken`, "
        "delta de texto da resposta), `done` (`ChatStreamDone`, fim normal, "
        "`content` é canônico) e `error` (`ChatStreamError`, falha — nunca "
        "acompanha `done`)."
    ),
)
def chat_stream(payload: ChatRequest) -> StreamingResponse:
    def event_source():
        for event, data in chat_service.stream_answer(
            payload.session_id, payload.message
        ):
            yield f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"

    return StreamingResponse(
        event_source(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            # Proxy que enfileira a resposta mata o streaming; o header pede para não bufferizar.
            "X-Accel-Buffering": "no",
        },
    )

"""Endpoint de chat com o agente. Router fino: histórico e stream ficam no service.

Uma conversa por `session_id`, gerado pelo cliente (webui) e persistida pelo
checkpointer do LangGraph no Postgres: sobrevive ao restart e é a mesma para
qualquer réplica que atenda a requisição.
"""

import json

from fastapi import APIRouter
from fastapi.responses import StreamingResponse

from resume_agent.api.schemas.chat import (
    ChatHistoryResponse,
    ChatRequest,
    ChatResponse,
)
from resume_agent.services import chat_service

router = APIRouter(prefix="/chat", tags=["Chat"])


@router.post(
    "",
    response_model=ChatResponse,
    summary="Conversar com o agente de currículos",
    description=(
        "Mesmo agente do REPL, exposto por HTTP. O histórico da conversa é "
        "persistido no Postgres por `session_id`: sobrevive a um restart e é "
        "compartilhado entre réplicas."
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
        "delta de texto da resposta), `reset` (`ChatStreamReset`, descarte o "
        "texto recebido até aqui — o guardrail reprovou a resposta e o modelo "
        "vai recomeçar), `done` (`ChatStreamDone`, fim normal, "
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


@router.get(
    "/{session_id}",
    response_model=ChatHistoryResponse,
    summary="Ler a conversa já persistida de uma sessão",
    description=(
        "Devolve a conversa do `session_id` em ordem cronológica, só com as "
        "falas do usuário e do agente. Sessão inexistente devolve lista vazia, "
        "não 404: para o cliente é o mesmo caso de conversa ainda não iniciada."
    ),
)
def chat_history(session_id: str) -> ChatHistoryResponse:
    return ChatHistoryResponse(
        session_id=session_id, messages=chat_service.history(session_id)
    )

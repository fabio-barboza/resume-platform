"""Endpoint de chat com o agente. Router fino: histórico fica em memória.

Uma conversa por `session_id`, gerado pelo cliente (webui) e perdida ao
reiniciar o processo — mesmo comportamento efêmero do REPL em `__main__.py`.
"""

from fastapi import APIRouter

from resume_agent.api.schemas.chat import ChatRequest, ChatResponse

router = APIRouter(prefix="/chat", tags=["Chat"])

_histories: dict[str, list[dict]] = {}


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
    from resume_agent.agent import agent

    history = _histories.setdefault(payload.session_id, [])
    history.append({"role": "user", "content": payload.message})
    result = agent.invoke({"messages": history})
    _histories[payload.session_id] = result["messages"]
    return ChatResponse(content=result["messages"][-1].content)

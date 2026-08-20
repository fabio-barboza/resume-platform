"""Histórico de conversa e tradução do stream do agente em eventos SSE.

O histórico é efêmero por processo: vive em memória por `session_id` e é
perdido no restart, mesmo comportamento do REPL em `__main__.py`.
"""

import logging
from collections.abc import Iterator

logger = logging.getLogger(__name__)

_histories: dict[str, list[dict]] = {}

# Nome do nó do modelo no grafo montado por `create_agent` (langchain 1.3.x).
# Confirmado em `agents/factory.py`: `graph.add_node("model", ...)`. Se a
# versão da lib mudar esse nome, o filtro de token do passo 1.2 para de
# funcionar silenciosamente — reconfirme aqui antes de mexer.
_MODEL_NODE = "model"


def ask(session_id: str, message: str) -> str:
    """Um turno sem streaming: invoke + grava histórico. Usado por `POST /chat`."""
    from resume_agent.agent import agent

    history = _histories.setdefault(session_id, [])
    history.append({"role": "user", "content": message})
    result = agent.invoke({"messages": history})
    _histories[session_id] = result["messages"]
    return result["messages"][-1].content


def stream_answer(session_id: str, message: str) -> Iterator[tuple[str, dict]]:
    """Percorre o stream do agente e traduz em eventos `(event_type, payload)`.

    Não formata SSE — quem serializa `data: <json>` é o router. Eventos:
    `start`, `tool` (status start/end), `token` (delta de texto) e, ao final,
    `done` ou `error` (nunca os dois).
    """
    from langchain_core.messages import AIMessageChunk, ToolMessage

    from resume_agent.agent import agent

    yield "start", {"session_id": session_id}

    history = _histories.setdefault(session_id, [])
    # Guardado antes do turno: se o cliente desconectar (`GeneratorExit`) ou o
    # modelo cair no meio, restauramos este valor em vez de deixar o histórico
    # com uma pergunta sem resposta — isso faz o próximo turno alucinar.
    previous_history = list(history)

    user_message = {"role": "user", "content": message}
    streamed: list[str] = []
    announced_tool_calls: set[str] = set()
    last_values: dict | None = None

    try:
        for mode, payload in agent.stream(
            {"messages": history + [user_message]},
            stream_mode=["messages", "values"],
        ):
            if mode == "values":
                last_values = payload
                for msg in payload.get("messages", []):
                    for call in getattr(msg, "tool_calls", None) or []:
                        call_id = call.get("id")
                        if call_id and call_id not in announced_tool_calls:
                            announced_tool_calls.add(call_id)
                            yield "tool", {"name": call.get("name"), "status": "start"}
                continue

            # mode == "messages": payload é (chunk, metadata)
            chunk, metadata = payload

            if isinstance(chunk, ToolMessage):
                yield "tool", {"name": chunk.name, "status": "end"}
                continue

            if not isinstance(chunk, AIMessageChunk):
                continue

            # Chamada do classificador do guardrail (guardrails/discrimination.py)
            # é tagueada com "guardrail" para nunca aparecer na tela do usuário —
            # sem essa tag e este filtro, o texto da classificação vaza como token.
            if "guardrail" in (metadata.get("tags") or []):
                continue

            if metadata.get("langgraph_node") != _MODEL_NODE:
                continue

            text = chunk.text if hasattr(chunk, "text") else None
            if text is None:
                content = chunk.content
                if isinstance(content, list):
                    text = "".join(
                        block.get("text", "")
                        for block in content
                        if isinstance(block, dict) and block.get("type") == "text"
                    )
                else:
                    text = content or ""

            if not text:
                # Chunk só com `tool_call_chunks` (argumento de tool sendo
                # montado): nunca vai para a tela.
                continue

            streamed.append(text)
            yield "token", {"text": text}
    except GeneratorExit:
        _histories[session_id] = previous_history
        raise
    except Exception:
        logger.exception(
            "Falha ao gerar a resposta em streaming (session_id=%s).", session_id
        )
        _histories[session_id] = previous_history
        yield "error", {"detail": "Falha ao gerar a resposta. Tente novamente."}
        return

    if last_values is None:
        _histories[session_id] = previous_history
        yield "error", {"detail": "Falha ao gerar a resposta. Tente novamente."}
        return

    final = last_values["messages"][-1].content
    if not "".join(streamed) and final:
        # Cobre guardrail (resposta pronta sem passar pelo nó do modelo),
        # provedor sem streaming e resposta inteira vinda num chunk só.
        yield "token", {"text": final}

    _histories[session_id] = last_values["messages"]
    yield "done", {"content": final}

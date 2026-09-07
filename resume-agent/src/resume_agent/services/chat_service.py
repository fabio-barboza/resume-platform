"""Histórico de conversa e tradução do stream do agente em eventos SSE.

O histórico é persistido pelo checkpointer do LangGraph no Postgres
(`infra/checkpointer.py`), com `thread_id` igual ao `session_id` do cliente.
Não vive mais na memória do processo: sobrevive ao restart e é compartilhado
entre réplicas, que é o que permite mais de um pod atender a mesma conversa.

Como o estado está no banco, cada turno envia só a mensagem nova — o LangGraph
carrega o resto do checkpoint.
"""

import logging
from collections.abc import Iterator

logger = logging.getLogger(__name__)

# Nome do nó do modelo no grafo montado por `create_agent` (langchain 1.3.x).
# Confirmado em `agents/factory.py`: `graph.add_node("model", ...)`. Se a
# versão da lib mudar esse nome, o filtro de token do passo 1.2 para de
# funcionar silenciosamente — reconfirme aqui antes de mexer.
_MODEL_NODE = "model"


def _config(session_id: str) -> dict:
    """Endereço da conversa para o checkpointer."""
    return {"configurable": {"thread_id": session_id}}


def _message_ids(agent, config: dict) -> set[str]:
    """Ids das mensagens já persistidas na thread, antes do turno começar."""
    state = agent.get_state(config)
    messages = (state.values or {}).get("messages", [])
    return {m.id for m in messages if getattr(m, "id", None)}


def _rollback_turn(agent, config: dict, previous_ids: set[str]) -> None:
    """Apaga da thread tudo que este turno gravou.

    O checkpointer grava a pergunta do usuário assim que o primeiro passo do
    grafo termina — antes, portanto, de existir resposta. Se o cliente
    desconectar ou o modelo cair no meio, a thread fica com pergunta órfã e o
    turno seguinte alucina em cima dela. Aqui removemos por id o que apareceu
    depois do início do turno, o que devolve a thread ao estado anterior.

    Falha ao reverter é logada e engolida: quem chama já está tratando um erro,
    e mascarar o erro original por causa da compensação seria pior.
    """
    from langchain_core.messages import RemoveMessage

    try:
        state = agent.get_state(config)
        added = [
            m
            for m in (state.values or {}).get("messages", [])
            if getattr(m, "id", None) and m.id not in previous_ids
        ]
        if added:
            agent.update_state(
                config, {"messages": [RemoveMessage(id=m.id) for m in added]}
            )
    except Exception:
        logger.exception(
            "Falha ao reverter o turno interrompido (thread_id=%s). O histórico "
            "pode ter ficado com uma pergunta sem resposta.",
            config["configurable"]["thread_id"],
        )


def ask(session_id: str, message: str) -> str:
    """Um turno sem streaming: invoke + checkpoint. Usado por `POST /chat`."""
    from resume_agent.agent import agent

    config = _config(session_id)
    previous_ids = _message_ids(agent, config)
    try:
        result = agent.invoke(
            {"messages": [{"role": "user", "content": message}]}, config
        )
    except Exception:
        _rollback_turn(agent, config, previous_ids)
        raise
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

    config = _config(session_id)
    # Fotografado antes do turno: se o cliente desconectar (`GeneratorExit`) ou
    # o modelo cair no meio, é por esta lista que `_rollback_turn` sabe o que
    # apagar do checkpoint.
    previous_ids = _message_ids(agent, config)

    user_message = {"role": "user", "content": message}
    streamed: list[str] = []
    announced_tool_calls: set[str] = set()
    last_values: dict | None = None

    try:
        for mode, payload in agent.stream(
            {"messages": [user_message]},
            config,
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
        _rollback_turn(agent, config, previous_ids)
        raise
    except Exception:
        logger.exception(
            "Falha ao gerar a resposta em streaming (session_id=%s).", session_id
        )
        _rollback_turn(agent, config, previous_ids)
        yield "error", {"detail": "Falha ao gerar a resposta. Tente novamente."}
        return

    if last_values is None:
        _rollback_turn(agent, config, previous_ids)
        yield "error", {"detail": "Falha ao gerar a resposta. Tente novamente."}
        return

    final = last_values["messages"][-1].content
    if not "".join(streamed) and final:
        # Cobre guardrail (resposta pronta sem passar pelo nó do modelo),
        # provedor sem streaming e resposta inteira vinda num chunk só.
        yield "token", {"text": final}

    yield "done", {"content": final}

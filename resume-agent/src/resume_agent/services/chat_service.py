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

# Marca da `HumanMessage` que o guardrail de grounding injeta ao reprovar uma
# resposta. Definida em `guardrails/grounding.py`; repetida aqui para não
# importar o guardrail só por uma string.
_GROUNDING_RETRY_FLAG = "grounding_retry"


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


def history(session_id: str) -> list[dict[str, str]]:
    """A conversa como a tela deve mostrá-la: só pergunta e resposta.

    Existe porque o checkpointer tornou a thread eterna: a webui abre vazia e
    o agente continua no turno anterior, então o usuário faz uma pergunta nova
    achando que começou do zero e recebe resposta influenciada pelo que não vê.

    Fica de fora o que é maquinaria e não conversa: resultado de ferramenta,
    a `AIMessage` que carrega `tool_calls` — mesmo quando também tem texto, que
    é o agente comentando antes de buscar de novo e nunca foi para a tela — e a
    `HumanMessage` de correção do guardrail de grounding, que apareceria como
    se o usuário a tivesse digitado.
    """
    from langchain_core.messages import AIMessage, HumanMessage

    from resume_agent.agent import agent

    messages = (agent.get_state(_config(session_id)).values or {}).get("messages", [])
    turns = []
    for message in messages:
        content = getattr(message, "content", None)
        if not isinstance(content, str) or not content:
            continue
        if isinstance(message, HumanMessage):
            if message.additional_kwargs.get(_GROUNDING_RETRY_FLAG):
                continue
            turns.append({"role": "user", "content": content})
        elif isinstance(message, AIMessage) and not message.tool_calls:
            turns.append({"role": "assistant", "content": content})
    return turns


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
    `start`, `tool` (status start/end), `token` (o texto da resposta) e, ao
    final, `done` ou `error` (nunca os dois).

    O texto sai num `token` só, depois do turno fechar, e não em delta por
    delta. O motivo é o guardrail de grounding: ele julga a resposta em
    `after_model`, quando os deltas já teriam ido para a tela. Reprovando, ou
    ele manda o modelo responder de novo (`jump_to="model"`) ou substitui a
    resposta pela recusa — nos dois casos o que o usuário já viu não é o que
    fica no histórico, e a segunda resposta aparecia colada na primeira.

    O preço é não ter mais o texto surgindo aos poucos: a tela fica no
    indicador de atividade até a resposta fechar. Os eventos de `tool`
    continuam saindo em tempo real, então o progresso da busca ainda aparece.
    """
    from langchain_core.messages import ToolMessage

    from resume_agent.agent import agent

    yield "start", {"session_id": session_id}

    config = _config(session_id)
    # Fotografado antes do turno: se o cliente desconectar (`GeneratorExit`) ou
    # o modelo cair no meio, é por esta lista que `_rollback_turn` sabe o que
    # apagar do checkpoint.
    previous_ids = _message_ids(agent, config)

    user_message = {"role": "user", "content": message}
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

            # mode == "messages": payload é (chunk, metadata). Só o fim de
            # ferramenta interessa aqui — o texto da resposta sai no final,
            # depois do veredito do guardrail.
            chunk, _ = payload

            if isinstance(chunk, ToolMessage):
                yield "tool", {"name": chunk.name, "status": "end"}
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

    # A resposta sai do estado final, nunca dos deltas: é o único ponto em que
    # o veredito do guardrail já está aplicado.
    final = last_values["messages"][-1].content
    if final:
        yield "token", {"text": final}

    yield "done", {"content": final}

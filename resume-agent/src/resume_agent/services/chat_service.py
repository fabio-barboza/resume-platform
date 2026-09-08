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


def _chunk_text(chunk) -> str:
    """Texto de um chunk do modelo, com `content` em string ou em blocos."""
    text = chunk.text if hasattr(chunk, "text") else None
    if text is not None:
        return text
    content = chunk.content
    if isinstance(content, list):
        return "".join(
            block.get("text", "")
            for block in content
            if isinstance(block, dict) and block.get("type") == "text"
        )
    return content or ""


def _has_new_grounding_retry(values: dict, seen: set[str]) -> bool:
    """Apareceu uma correção do guardrail que ainda não tínhamos visto?

    A correção é uma `HumanMessage` marcada, injetada quando a resposta é
    reprovada. Vê-la no estado significa que o texto já emitido foi descartado
    e o modelo vai responder de novo. Guardamos os ids porque o mesmo `values`
    reaparece a cada passo do grafo.
    """
    novo = False
    for message in values.get("messages", []):
        if not message.additional_kwargs.get(_GROUNDING_RETRY_FLAG):
            continue
        message_id = getattr(message, "id", None)
        if message_id and message_id not in seen:
            seen.add(message_id)
            novo = True
    return novo


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

    O guardrail de grounding julga a resposta em `after_model`, depois de ela
    ter saído inteira em deltas. Reprovando, ele manda o modelo responder de
    novo (`jump_to="model"`) ou troca a resposta pela recusa — nos dois casos
    o texto já emitido não é o que fica no histórico.

    Daí o evento `reset`: ao detectar que o modelo recomeçou, mandamos o
    cliente descartar o que acumulou. A webui não pinta os tokens na tela
    justamente por isso (ela mostra o status enquanto o turno corre e só
    renderiza no `done`), mas os acumula para o caso de interrupção — e o
    `done` no fim traz o texto canônico, então uma reprovação que escape aqui
    ainda é corrigida lá.
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
    announced_tool_calls: set[str] = set()
    # Texto já emitido nesta passada do modelo. Zera a cada `reset`, para o que
    # o cliente tem na tela e o que contamos aqui não divergirem.
    streamed: list[str] = []
    retries_seen: set[str] = set()
    last_values: dict | None = None

    try:
        for mode, payload in agent.stream(
            {"messages": [user_message]},
            config,
            stream_mode=["messages", "values"],
        ):
            if mode == "values":
                last_values = payload
                # A correção do guardrail entrando no estado é o sinal de que a
                # resposta anterior foi descartada e o modelo vai recomeçar. O
                # cliente já renderizou aquele texto: mandamos apagar.
                if streamed and _has_new_grounding_retry(payload, retries_seen):
                    streamed.clear()
                    yield "reset", {}
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

            # A classificação do guardrail de critério protegido é tagueada
            # (guardrails/discrimination.py). Sem este filtro o texto dela vaza
            # como token do agente.
            if "guardrail" in (metadata.get("tags") or []):
                continue

            if metadata.get("langgraph_node") != _MODEL_NODE:
                continue

            text = _chunk_text(chunk)
            if not text:
                # Chunk só com `tool_call_chunks`: argumento de ferramenta
                # sendo montado, nunca vai para a tela.
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
    # Cobre o que o `reset` não alcança: a segunda reprovação do guardrail não
    # faz `jump_to`, troca a `AIMessage` pela recusa mantendo o `id`, e o texto
    # descartado já saiu como token. Também cobre resposta sem passar pelo nó do
    # modelo e provedor que não faz streaming.
    if "".join(streamed) != final and final:
        yield "reset", {}
        yield "token", {"text": final}

    yield "done", {"content": final}

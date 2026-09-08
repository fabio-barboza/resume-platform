"""Testes determinísticos de `POST /chat/stream` e `POST /chat`.

Sem marcador `eval`: nenhum teste chama LLM de verdade nem toca o Postgres. O
agente usado pelo `chat_service` é substituído por um fake, via monkeypatch de
`resume_agent.agent.agent` — é esse atributo que o import tardio em
`chat_service` resolve a cada chamada.

O fake também finge ser o checkpointer: guarda as mensagens por `thread_id` e
implementa `get_state`/`update_state`. Sem isso não dá para testar a
compensação de turno interrompido, que é justamente o que evita pergunta órfã
no histórico persistido.
"""

import json
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from langchain_core.messages import (
    AIMessage,
    AIMessageChunk,
    RemoveMessage,
    ToolMessage,
)

from resume_agent.api.main import app
from resume_agent.services import chat_service


class _FakeAgent:
    """Agente fake com checkpointer de mentira.

    `.stream()` reproduz uma sequência fixa de `(mode, payload)`. O estado por
    `thread_id` é atualizado a partir do último `values` visto, e a mensagem do
    usuário entra assim que o turno começa — igual ao checkpointer de verdade,
    que é o motivo de a pergunta órfã existir.
    """

    def __init__(self, events=None, invoke_result=None, raise_after=None):
        self._events = events or []
        self._invoke_result = invoke_result
        self._raise_after = raise_after
        self._threads: dict[str, list] = {}

    @staticmethod
    def _thread_id(config):
        return config["configurable"]["thread_id"]

    def get_state(self, config):
        messages = self._threads.get(self._thread_id(config), [])
        return SimpleNamespace(values={"messages": list(messages)})

    def update_state(self, config, values):
        removed = {
            m.id for m in values.get("messages", []) if isinstance(m, RemoveMessage)
        }
        thread = self._thread_id(config)
        self._threads[thread] = [
            m for m in self._threads.get(thread, []) if m.id not in removed
        ]

    def _record_user_message(self, config, payload):
        """Persiste a pergunta antes de existir resposta, como o checkpointer."""
        thread = self._thread_id(config)
        for message in payload.get("messages", []):
            self._threads.setdefault(thread, []).append(
                AIMessage(
                    content=message["content"], id=f"user-{len(self._threads[thread])}"
                )
            )

    def stream(self, payload, config, stream_mode):
        self._record_user_message(config, payload)
        thread = self._thread_id(config)
        for i, event in enumerate(self._events):
            if self._raise_after is not None and i == self._raise_after:
                raise RuntimeError("falha simulada do provedor")
            mode, data = event
            if mode == "values":
                for message in data["messages"]:
                    if message.id is None:
                        message.id = f"ai-{thread}-{len(self._threads[thread])}"
                    self._threads[thread].append(message)
            yield event

    def invoke(self, payload, config):
        self._record_user_message(config, payload)
        return self._invoke_result


def _install_fake_agent(monkeypatch, fake):
    import resume_agent.agent as agent_module

    monkeypatch.setattr(agent_module, "agent", fake)


def _parse_sse(body: str) -> list[tuple[str, dict]]:
    """Quebra o corpo cru do SSE em `(event, data)`, validando a forma do frame."""
    frames = [f for f in body.split("\n\n") if f.strip()]
    parsed = []
    for frame in frames:
        lines = frame.split("\n")
        assert len(lines) == 2, f"frame com forma inesperada: {frame!r}"
        assert lines[0].startswith("event: ")
        assert lines[1].startswith("data: ")
        event = lines[0][len("event: ") :]
        data_raw = lines[1][len("data: ") :]
        assert "\n" not in data_raw
        data = json.loads(data_raw)
        parsed.append((event, data))
    return parsed


@pytest.fixture
def client():
    with TestClient(app) as c:
        yield c


def _token_chunk(text: str, tags: list[str] | None = None):
    metadata = {"langgraph_node": "model", "tags": tags or []}
    return ("messages", (AIMessageChunk(content=text), metadata))


def _tool_call_values(name: str, call_id: str):
    ai_msg = AIMessage(
        content="", tool_calls=[{"name": name, "args": {}, "id": call_id}]
    )
    return ("values", {"messages": [ai_msg]})


def _tool_result_message(name: str, content: str, call_id: str):
    metadata = {"langgraph_node": "tools", "tags": []}
    return (
        "messages",
        (ToolMessage(content=content, name=name, tool_call_id=call_id), metadata),
    )


def _final_values(content: str):
    return ("values", {"messages": [AIMessage(content=content)]})


class TestChatStreamShape:
    def test_ordem_e_forma(self, client, monkeypatch):
        events = [
            _token_chunk("Olá"),
            _token_chunk(", mundo"),
            _final_values("Olá, mundo"),
        ]
        _install_fake_agent(monkeypatch, _FakeAgent(events=events))

        resp = client.post("/chat/stream", json={"session_id": "s1", "message": "oi"})
        assert resp.status_code == 200
        frames = _parse_sse(resp.text)

        assert frames[0][0] == "start"
        assert frames[0][1] == {"session_id": "s1"}
        assert frames[-1][0] == "done"

    def test_concatenacao_tokens_igual_done(self, client, monkeypatch):
        events = [
            _token_chunk("parte 1 "),
            _token_chunk("parte 2"),
            _final_values("parte 1 parte 2"),
        ]
        _install_fake_agent(monkeypatch, _FakeAgent(events=events))

        resp = client.post("/chat/stream", json={"session_id": "s2", "message": "oi"})
        frames = _parse_sse(resp.text)

        tokens = "".join(data["text"] for event, data in frames if event == "token")
        done = next(data for event, data in frames if event == "done")
        assert tokens == done["content"]

    def test_fallback_sem_token(self, client, monkeypatch):
        # Caso guardrail: só `values`, nenhum chunk de mensagem passa pelo nó "model".
        events = [_final_values("Não filtro por esse critério.")]
        _install_fake_agent(monkeypatch, _FakeAgent(events=events))

        resp = client.post("/chat/stream", json={"session_id": "s3", "message": "oi"})
        frames = _parse_sse(resp.text)

        token_events = [data for event, data in frames if event == "token"]
        assert len(token_events) == 1
        assert token_events[0]["text"] == "Não filtro por esse critério."
        assert frames[-1] == ("done", {"content": "Não filtro por esse critério."})

    def test_tool_nao_vaza_conteudo(self, client, monkeypatch):
        resume_text = "Rafael Mendes, telefone 5511999999999, experiência com Cobol"
        events = [
            _tool_call_values("find_candidate_by_name", "call-1"),
            _tool_result_message("find_candidate_by_name", resume_text, "call-1"),
            _token_chunk("Encontrei o candidato."),
            _final_values("Encontrei o candidato."),
        ]
        _install_fake_agent(monkeypatch, _FakeAgent(events=events))

        resp = client.post("/chat/stream", json={"session_id": "s4", "message": "oi"})
        frames = _parse_sse(resp.text)

        for event, data in frames:
            if event == "token":
                assert resume_text not in data["text"]

        tool_events = [data for event, data in frames if event == "tool"]
        assert {"name": "find_candidate_by_name", "status": "start"} in tool_events
        assert {"name": "find_candidate_by_name", "status": "end"} in tool_events

    def test_resposta_reprovada_pelo_grounding_nao_chega_na_tela(
        self, client, monkeypatch
    ):
        """O que o usuário vê é a resposta final, não a que o guardrail descartou.

        O guardrail de grounding julga em `after_model`: emitindo delta por
        delta, a resposta reprovada já teria ido para a tela quando o veredito
        sai, e a segunda aparecia colada na primeira.
        """
        events = [
            # 1ª passada: resposta inventada, que o guardrail vai descartar
            _token_chunk("Recomendo Fulano de Tal"),
            _token_chunk(" e Beltrano da Silva."),
            # jump_to="model" → 2ª passada, esta é a que vale
            _token_chunk("Encontrei"),
            _token_chunk(" Diego Santana."),
            _final_values("Encontrei Diego Santana."),
        ]
        _install_fake_agent(monkeypatch, _FakeAgent(events=events))

        resp = client.post("/chat/stream", json={"session_id": "g2", "message": "oi"})
        frames = _parse_sse(resp.text)
        tokens = "".join(data["text"] for event, data in frames if event == "token")

        assert "Fulano de Tal" not in tokens
        assert tokens == "Encontrei Diego Santana."

    def test_guardrail_nao_vaza(self, client, monkeypatch):
        events = [
            _token_chunk("classificação interna vazando", tags=["guardrail"]),
            _final_values("Não filtro por esse critério."),
        ]
        _install_fake_agent(monkeypatch, _FakeAgent(events=events))

        resp = client.post("/chat/stream", json={"session_id": "s5", "message": "oi"})
        frames = _parse_sse(resp.text)

        for event, data in frames:
            if event == "token":
                assert "classificação interna vazando" not in data["text"]


class TestHistorico:
    """Histórico agora mora no checkpointer; os testes o leem por `get_state`."""

    @staticmethod
    def _messages(agent, thread_id):
        config = {"configurable": {"thread_id": thread_id}}
        return agent.get_state(config).values["messages"]

    def test_historico_apos_sucesso(self, monkeypatch):
        first_events = [
            _token_chunk("primeira resposta"),
            _final_values("primeira resposta"),
        ]
        fake = _FakeAgent(events=first_events)

        import resume_agent.agent as agent_module

        monkeypatch.setattr(agent_module, "agent", fake)

        list(chat_service.stream_answer("hist-1", "primeira pergunta"))

        assert self._messages(fake, "hist-1")[-1].content == "primeira resposta"

        # próximo turno da mesma sessão acumula no mesmo `thread_id`
        fake._events = [_final_values("segunda resposta")]
        list(chat_service.stream_answer("hist-1", "segunda pergunta"))

        messages = self._messages(fake, "hist-1")
        assert messages[-1].content == "segunda resposta"
        assert [m.content for m in messages] == [
            "primeira pergunta",
            "primeira resposta",
            "segunda pergunta",
            "segunda resposta",
        ]

    def test_historico_preservado_apos_excecao(self, monkeypatch):
        import resume_agent.agent as agent_module

        fake = _FakeAgent(events=[_final_values("resposta ok")])
        monkeypatch.setattr(agent_module, "agent", fake)
        list(chat_service.stream_answer("hist-2", "pergunta 1"))
        history_before = list(self._messages(fake, "hist-2"))

        # segundo turno levanta exceção no meio do stream, depois de o
        # checkpointer já ter gravado a pergunta
        fake._events = [_token_chunk("começando..."), _final_values("nunca chega")]
        fake._raise_after = 1

        results = list(chat_service.stream_answer("hist-2", "pergunta 2"))
        assert results[-1][0] == "error"
        assert not any(event == "done" for event, _ in results)

        # a pergunta órfã foi removida: o turno seguinte não parte dela
        assert self._messages(fake, "hist-2") == history_before

    def test_pergunta_orfa_removida_em_desconexao(self, monkeypatch):
        """Cliente fecha a aba no meio do stream: nada do turno fica gravado."""
        import resume_agent.agent as agent_module

        # O corte é no evento de ferramenta: como o texto só sai depois do
        # veredito do guardrail, é o único yield que acontece com o turno ainda
        # aberto.
        fake = _FakeAgent(
            events=[
                _tool_call_values("find_in_resumes", "call-1"),
                _tool_result_message("find_in_resumes", "trecho", "call-1"),
                _final_values("fim"),
            ]
        )
        monkeypatch.setattr(agent_module, "agent", fake)

        stream = chat_service.stream_answer("hist-3", "pergunta abandonada")
        next(stream)  # start
        next(stream)  # tool start, turno ainda aberto
        stream.close()  # GeneratorExit

        assert self._messages(fake, "hist-3") == []


class TestHistoricoDaTela:
    """`GET /chat/{session_id}`: o que a webui monta ao abrir a página.

    Existe porque o checkpointer tornou a thread eterna: sem restaurar a
    conversa, a tela abre vazia enquanto o agente segue no turno anterior, e o
    usuário recebe resposta influenciada por um contexto que não vê.
    """

    def test_devolve_pergunta_e_resposta_em_ordem(self, client, monkeypatch):
        from langchain_core.messages import AIMessage, HumanMessage

        fake = _FakeAgent()
        fake._threads["h1"] = [
            HumanMessage(content="quem sabe Python?", id="u1"),
            AIMessage(content="Encontrei Diego Santana.", id="a1"),
        ]
        _install_fake_agent(monkeypatch, fake)

        body = client.get("/chat/h1").json()

        assert body["session_id"] == "h1"
        assert body["messages"] == [
            {"role": "user", "content": "quem sabe Python?"},
            {"role": "assistant", "content": "Encontrei Diego Santana."},
        ]

    def test_omite_o_que_nunca_foi_para_a_tela(self, client, monkeypatch):
        """Ferramenta, preâmbulo com tool_calls e correção do guardrail."""
        from langchain_core.messages import AIMessage, HumanMessage, ToolMessage

        fake = _FakeAgent()
        fake._threads["h2"] = [
            HumanMessage(content="quem sabe Python?", id="u1"),
            AIMessage(
                content="Vou buscar na base.",
                id="a1",
                tool_calls=[{"name": "find_in_resumes", "args": {}, "id": "c1"}],
            ),
            ToolMessage(content="trecho do currículo", tool_call_id="c1", id="t1"),
            HumanMessage(
                content="Correção automática do sistema, não do usuário: ...",
                id="u2",
                additional_kwargs={"grounding_retry": True},
            ),
            AIMessage(content="Encontrei Diego Santana.", id="a2"),
        ]
        _install_fake_agent(monkeypatch, fake)

        messages = client.get("/chat/h2").json()["messages"]

        assert messages == [
            {"role": "user", "content": "quem sabe Python?"},
            {"role": "assistant", "content": "Encontrei Diego Santana."},
        ]

    def test_sessao_inexistente_devolve_lista_vazia(self, client, monkeypatch):
        """Sessão nova e sessão sem histórico são o mesmo caso para o cliente."""
        _install_fake_agent(monkeypatch, _FakeAgent())

        resp = client.get("/chat/nunca-usada")

        assert resp.status_code == 200
        assert resp.json()["messages"] == []


class TestChatSemStream:
    def test_post_chat_intacto(self, client, monkeypatch):
        import resume_agent.agent as agent_module

        fake_result = {"messages": [AIMessage(content="resposta sem streaming")]}
        monkeypatch.setattr(
            agent_module, "agent", _FakeAgent(invoke_result=fake_result)
        )

        resp = client.post("/chat", json={"session_id": "s6", "message": "oi"})
        assert resp.status_code == 200
        assert resp.json() == {"content": "resposta sem streaming"}

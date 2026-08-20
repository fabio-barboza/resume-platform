"""Testes determinísticos de `POST /chat/stream` e `POST /chat`.

Sem marcador `eval`: nenhum teste chama LLM de verdade. O agente usado pelo
`chat_service` é substituído por um fake cujo `.stream()`/`.invoke()` devolve
uma sequência fixa, via monkeypatch de `resume_agent.agent.agent` — é esse
atributo que o import tardio em `chat_service` resolve a cada chamada.
"""

import json

import pytest
from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage, AIMessageChunk, ToolMessage

from resume_agent.api.main import app
from resume_agent.services import chat_service


class _FakeAgent:
    """Agente fake: `.stream()` reproduz uma sequência fixa de `(mode, payload)`."""

    def __init__(self, events=None, invoke_result=None, raise_after=None):
        self._events = events or []
        self._invoke_result = invoke_result
        self._raise_after = raise_after

    def stream(self, _input, stream_mode):
        for i, event in enumerate(self._events):
            if self._raise_after is not None and i == self._raise_after:
                raise RuntimeError("falha simulada do provedor")
            yield event

    def invoke(self, _input):
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
        # precisa ser JSON de uma linha só
        assert "\n" not in data_raw
        data = json.loads(data_raw)
        parsed.append((event, data))
    return parsed


@pytest.fixture(autouse=True)
def _clear_histories():
    chat_service._histories.clear()
    yield
    chat_service._histories.clear()


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
    def test_historico_apos_sucesso(self, monkeypatch):
        first_events = [
            _token_chunk("primeira resposta"),
            _final_values("primeira resposta"),
        ]
        fake = _FakeAgent(events=first_events)

        import resume_agent.agent as agent_module

        monkeypatch.setattr(agent_module, "agent", fake)

        list(chat_service.stream_answer("hist-1", "primeira pergunta"))

        history_after = chat_service._histories["hist-1"]
        assert history_after[-1].content == "primeira resposta"

        # próximo turno da mesma sessão parte do histórico anterior
        second_events = [_final_values("segunda resposta")]
        fake._events = second_events
        list(chat_service.stream_answer("hist-1", "segunda pergunta"))

        assert chat_service._histories["hist-1"][-1].content == "segunda resposta"

    def test_historico_preservado_apos_excecao(self, monkeypatch):
        import resume_agent.agent as agent_module

        # primeiro turno, bem-sucedido, estabelece o histórico "antes"
        fake = _FakeAgent(events=[_final_values("resposta ok")])
        monkeypatch.setattr(agent_module, "agent", fake)
        list(chat_service.stream_answer("hist-2", "pergunta 1"))
        history_before = list(chat_service._histories["hist-2"])

        # segundo turno levanta exceção no meio do stream
        failing = _FakeAgent(
            events=[_token_chunk("começando..."), _final_values("nunca chega")],
            raise_after=1,
        )
        monkeypatch.setattr(agent_module, "agent", failing)

        results = list(chat_service.stream_answer("hist-2", "pergunta 2"))
        assert results[-1][0] == "error"
        assert not any(event == "done" for event, _ in results)

        assert chat_service._histories["hist-2"] == history_before


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

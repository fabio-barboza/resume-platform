"""Liga e desliga o tracing do Langfuse num lugar só.

Observabilidade é opcional: sem `LANGFUSE_ENABLED=true` no `.env`, a aplicação
roda igual — nenhum callback anexado ao agente, nenhuma observation criada em
volta dos embeddings, nenhuma chamada de rede a mais. Ligado, o Langfuse
precisa das chaves (`LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY`) e da
`LANGFUSE_BASE_URL`.

O desligado é o padrão de propósito: quem clona o repositório para experimentar
não deve precisar de um Langfuse no ar, e um trace que ninguém vai ler não
justifica uma dependência externa na subida.

Se o tracing estiver ligado mas o Langfuse não inicializar — chave errada,
serviço fora do ar —, o import não quebra: registra um aviso e segue sem
tracing. Instrumentação nunca derruba a aplicação que ela observa.
"""

import logging
import os
from contextlib import contextmanager, nullcontext

from dotenv import load_dotenv

load_dotenv()

logger = logging.getLogger(__name__)

_TRUTHY = {"1", "true", "yes", "on"}


def _is_enabled() -> bool:
    return os.getenv("LANGFUSE_ENABLED", "false").strip().lower() in _TRUTHY


class _NoopObservation:
    """Observation de mentira, para o chamador não precisar saber se há tracing."""

    def update(self, *args, **kwargs) -> None:
        pass


_NOOP = _NoopObservation()


def _build_handler():
    """Callback do LangChain, ou None quando não há (ou não deu para ter) tracing."""
    if not _is_enabled():
        return None
    try:
        from langfuse.langchain import CallbackHandler

        return CallbackHandler()
    except Exception as exc:  # noqa: BLE001 - qualquer falha aqui vira "sem tracing"
        logger.warning(
            "LANGFUSE_ENABLED=true, mas o Langfuse não inicializou (%s). "
            "Seguindo sem tracing.",
            exc,
        )
        return None


_handler = _build_handler()

#: Verdadeiro só quando o tracing foi pedido *e* inicializou.
ENABLED = _handler is not None


def callbacks() -> list:
    """Callbacks para o `RunnableConfig`. Lista vazia desliga o tracing."""
    return [_handler] if _handler else []


def _observation_cm(name: str, **kwargs):
    """Context manager da observation, ou um `nullcontext` quando não há tracing.

    O `get_client()` é resolvido aqui, e não no import, porque é ele que fala
    com o Langfuse: falhando, a observation vira no-op em vez de estourar no
    meio do embedding.
    """
    if not ENABLED:
        return nullcontext(_NOOP)
    try:
        from langfuse import get_client

        return get_client().start_as_current_observation(name=name, **kwargs)
    except Exception as exc:  # noqa: BLE001
        logger.warning("Langfuse indisponível (%s) — observation %r ignorada.", exc, name)
        return nullcontext(_NOOP)


@contextmanager
def observation(name: str, **kwargs):
    """Observation do Langfuse quando ligado, no-op quando desligado.

    O objeto entregue sempre responde a `.update(...)`, então o código
    instrumentado é o mesmo nos dois casos.
    """
    with _observation_cm(name, **kwargs) as obs:
        yield obs if obs is not None else _NOOP

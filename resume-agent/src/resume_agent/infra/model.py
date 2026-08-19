import os
from typing import List

from dotenv import load_dotenv
from langchain.chat_models import init_chat_model
from langchain_openai import OpenAIEmbeddings
from pydantic import SecretStr

from resume_agent.infra import observability

load_dotenv()

# O agente principal e o worker (chamada de LLM que resume a pesquisa) podem
# viver em provedores diferentes: cada papel tem seu próprio endpoint, chave,
# provider e modelo. As variáveis genéricas (MODEL_*) servem de fallback
# quando as específicas não existem, então apontar os dois para o mesmo
# provedor continua sendo um só valor.
_DEFAULT_BASE_URL = os.getenv("MODEL_BASE_URL", "http://localhost:8200/v1")
_DEFAULT_API_KEY = os.getenv("MODEL_API_KEY", "not-needed")
_DEFAULT_PROVIDER = os.getenv("MODEL_PROVIDER", "openai")


def _role_config(role: str) -> dict:
    """Lê a config de um papel (MAIN/WORKER), caindo nos genéricos."""
    return {
        "model": os.getenv(f"{role}_MODEL", "qwen3.6:35B"),
        "base_url": os.getenv(f"{role}_MODEL_BASE_URL", _DEFAULT_BASE_URL),
        "api_key": os.getenv(f"{role}_MODEL_API_KEY", _DEFAULT_API_KEY),
        "provider": os.getenv(f"{role}_MODEL_PROVIDER", _DEFAULT_PROVIDER),
    }


_MAIN = _role_config("MAIN")
_WORKER = _role_config("WORKER")

_EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "qwen3-embedding-0.6b")
_EMBEDDING_BASE_URL = os.getenv("EMBEDDING_MODEL_BASE_URL", "http://localhost:8892/v1")
_EMBEDDING_API_KEY = os.getenv("EMBEDDING_MODEL_API_KEY", _DEFAULT_API_KEY)


class _ObservedEmbeddings(OpenAIEmbeddings):
    """OpenAIEmbeddings que loga cada chamada como observation 'embedding' no Langfuse.

    Chama self.client.create diretamente (em vez de super().embed_documents) para
    capturar o usage.prompt_tokens real devolvido pelo servidor — a implementação da
    langchain_openai descarta esse campo e só repassa os vetores.

    Com o tracing desligado (`LANGFUSE_ENABLED` != true), a observation vira
    no-op e sobra só a chamada ao servidor de embeddings.
    """

    def _create(self, texts: List[str]) -> tuple[List[List[float]], dict]:
        self._ensure_sync_client_available()
        response = self.client.create(input=texts, **self._invocation_params)
        if not isinstance(response, dict):
            response = response.model_dump()
        embeddings = [r["embedding"] for r in response["data"]]
        usage = response.get("usage", {})
        return embeddings, usage

    def embed_query(self, text: str) -> List[float]:
        with observability.observation(
            name="embed_query",
            as_type="embedding",
            input=text,
            model=self.model,
            metadata={"base_url": self.openai_api_base},
        ) as obs:
            embeddings, usage = self._create([text])
            obs.update(usage_details={
                "input": usage.get("prompt_tokens", 0),
                "total": usage.get("total_tokens", 0),
            })
            return embeddings[0]

    def embed_documents(self, texts: List[str]) -> List[List[float]]:
        with observability.observation(
            name="embed_documents",
            as_type="embedding",
            input=f"{len(texts)} chunks",
            model=self.model,
            metadata={"base_url": self.openai_api_base},
        ) as obs:
            embeddings, usage = self._create(texts)
            obs.update(usage_details={
                "input": usage.get("prompt_tokens", 0),
                "total": usage.get("total_tokens", 0),
            })
            return embeddings


class Model:
    _instances = {}

    @staticmethod
    def get_model(
        model: str,
        base_url: str,
        api_key: str,
        provider: str,
        temperature: float = 0,
    ):
        # base_url entra na chave: mesmo nome de modelo em provedores
        # diferentes são instâncias distintas.
        key = (provider, base_url, model, temperature)
        if key not in Model._instances:
            Model._instances[key] = init_chat_model(
                model=model,
                model_provider=provider,
                base_url=base_url,
                api_key=api_key,
                temperature=temperature,
                stream_usage=True,
                extra_body={"chat_template_kwargs": {"enable_thinking": False}},
            )
        return Model._instances[key]

    @staticmethod
    def _from_config(cfg: dict, temperature: float):
        return Model.get_model(
            model=cfg["model"],
            base_url=cfg["base_url"],
            api_key=cfg["api_key"],
            provider=cfg["provider"],
            temperature=temperature,
        )

    @staticmethod
    def get_main_model(temperature: float = 0):
        """Modelo do agente principal (variáveis MAIN_MODEL* no .env)."""
        return Model._from_config(_MAIN, temperature)

    @staticmethod
    def get_worker_model(temperature: float = 0):
        """Modelo do worker: busca, RAG, extração, resumo (WORKER_MODEL* no .env)."""
        return Model._from_config(_WORKER, temperature)

    @staticmethod
    def get_factual_model():
        """Modelo determinístico (temperature=0). Alias do modelo principal."""
        return Model.get_main_model(temperature=0)

    @staticmethod
    def get_conversational_model():
        """Modelo criativo (temperature=0.7) para conversa e opiniões."""
        return Model.get_main_model(temperature=0.7)

    @staticmethod
    def get_embedding_model():
        """Embeddings Qwen3-Embedding-0.6B-Q8_0 (EMBEDDING_MODEL* no .env)."""
        key = ("embedding", _EMBEDDING_BASE_URL, _EMBEDDING_MODEL)
        if key not in Model._instances:
            Model._instances[key] = _ObservedEmbeddings(
                model=_EMBEDDING_MODEL,
                base_url=_EMBEDDING_BASE_URL,
                api_key=SecretStr(_EMBEDDING_API_KEY),
                check_embedding_ctx_length=False,
            )
        return Model._instances[key]

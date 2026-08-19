"""Extração de nome, email e telefone a partir do texto do currículo.

Roda uma vez, na ingestão, e o resultado é persistido — nunca em tempo de
consulta. Alimentada só com as primeiras páginas do documento: cabeçalho de
currículo é onde o contato mora, e mandar o PDF inteiro só adiciona ruído e
tokens.

O nome sai exclusivamente do LLM com structured output. Email e telefone têm
regex como rede de segurança: se o LLM devolver nulo ou falhar, ainda dá para
identificar o candidato pela chave natural.
"""

import logging
import os
import re

from pydantic import BaseModel, Field

from resume_agent.infra import Model

logger = logging.getLogger(__name__)

# Quantas páginas iniciais alimentam a extração.
EXTRACTION_PAGES = int(os.getenv("EXTRACTION_PAGES", "2"))

_EMAIL_RE = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")
# Telefone BR: DDD opcional entre parênteses, separador livre, 8 ou 9 dígitos.
_PHONE_RE = re.compile(
    r"(?:\+55[\s.-]?)?(?:\(?\d{2}\)?[\s.-]?)?\d{4,5}[\s.-]?\d{4}"
)

_PROMPT = """Extraia os dados de contato do candidato a partir do trecho de \
currículo abaixo.

Regras:
- `name`: o nome completo do candidato dono do currículo. Não confunda com \
nome de empresa, faculdade, curso, cliente ou referência profissional.
- `email`: o email de contato do próprio candidato.
- `phone`: o telefone de contato do próprio candidato.
- Se um dado não estiver no texto, devolva null. Não invente e não deduza.

Currículo:
---
{text}
---"""


class CandidateExtraction(BaseModel):
    """Dados do candidato extraídos do currículo. Ausente = null."""

    name: str | None = Field(
        default=None, description="Nome completo do candidato, ou null."
    )
    email: str | None = Field(
        default=None, description="Email de contato do candidato, ou null."
    )
    phone: str | None = Field(
        default=None, description="Telefone de contato do candidato, ou null."
    )


def _normalize(value: str | None) -> str | None:
    """String vazia, espaços e literais tipo 'null' viram None."""
    if value is None:
        return None
    cleaned = value.strip()
    if not cleaned or cleaned.lower() in {"null", "none", "n/a", "-"}:
        return None
    return cleaned


def _regex_email(text: str) -> str | None:
    match = _EMAIL_RE.search(text)
    return match.group(0) if match else None


def _regex_phone(text: str) -> str | None:
    match = _PHONE_RE.search(text)
    return match.group(0).strip() if match else None


def extract_candidate(text: str) -> CandidateExtraction:
    """Extrai os dados do candidato. Nunca levanta: falha vira campo nulo."""
    if not text.strip():
        return CandidateExtraction()

    extracted = CandidateExtraction()
    try:
        model = Model.get_factual_model().with_structured_output(CandidateExtraction)
        result = model.invoke(_PROMPT.format(text=text))
        if isinstance(result, CandidateExtraction):
            extracted = result
    except Exception:
        # Extração é best-effort: um currículo sem dados identificados entra
        # com status de revisão pendente em vez de derrubar a ingestão.
        logger.warning("Extração via LLM falhou; caindo no fallback por regex.")

    name = _normalize(extracted.name)
    email = _normalize(extracted.email) or _regex_email(text)
    phone = _normalize(extracted.phone) or _regex_phone(text)

    return CandidateExtraction(name=name, email=email, phone=phone)

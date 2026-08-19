"""Guarda dos PDFs num bucket S3 (AWS S3 ou compatível, ex.: MinIO).

O banco é a fonte da verdade da base vetorial; o arquivo fica no bucket ao
lado para permitir reprocessar ou auditar um currículo sem depender de novo
upload. Por isso as operações são best-effort: falha aqui não invalida a
ingestão.

Este módulo é o único que conhece o bucket, e fala com ele via `boto3` — o
SDK oficial da AWS. Em dev, `S3_ENDPOINT_URL` aponta para o MinIO local;
trocar para AWS S3 de verdade é só remover essa variável e apontar
`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`/`AWS_REGION` para uma conta real,
sem tocar em código.

Dois uploads podem chegar com o mesmo nome e conteúdo diferente, então quando
o nome está livre o objeto entra com o nome original; quando já está tomado
por outro conteúdo, entra com o hash no sufixo. O hash do conteúdo também vai
nos metadados do objeto (`x-amz-meta-sha256`), para checar duplicata sem
precisar baixar o arquivo inteiro de volta.
"""

import logging
import os
import re
import unicodedata

import boto3
from botocore.client import Config
from botocore.exceptions import BotoCoreError, ClientError
from dotenv import load_dotenv

load_dotenv()

logger = logging.getLogger(__name__)

AWS_ACCESS_KEY_ID = os.getenv("AWS_ACCESS_KEY_ID", "minio")
AWS_SECRET_ACCESS_KEY = os.getenv("AWS_SECRET_ACCESS_KEY", "minio123")
AWS_REGION = os.getenv("AWS_REGION", "us-east-1")
S3_BUCKET = os.getenv("S3_BUCKET", "resume-agent-bucket")
# Vazio (padrão) fala com a AWS de verdade. Em dev, aponta para o MinIO local.
S3_ENDPOINT_URL = os.getenv("S3_ENDPOINT_URL") or None

_HASH_META_KEY = "sha256"

_client = None


def _get_client():
    """Cliente S3, criado sob demanda. Garante que o bucket existe."""
    global _client
    if _client is None:
        client = boto3.client(
            "s3",
            endpoint_url=S3_ENDPOINT_URL,
            aws_access_key_id=AWS_ACCESS_KEY_ID,
            aws_secret_access_key=AWS_SECRET_ACCESS_KEY,
            region_name=AWS_REGION,
            config=Config(signature_version="s3v4"),
        )
        try:
            client.head_bucket(Bucket=S3_BUCKET)
        except (ClientError, BotoCoreError):
            client.create_bucket(Bucket=S3_BUCKET)
        _client = client
    return _client


def _slug(filename: str) -> str:
    stem = filename.rsplit("/", 1)[-1].removesuffix(".pdf")
    ascii_stem = unicodedata.normalize("NFKD", stem).encode("ascii", "ignore").decode()
    return re.sub(r"[^A-Za-z0-9_-]+", "_", ascii_stem).strip("_") or "curriculo"


def _candidate_keys(filename: str, digest: str) -> tuple[str, str]:
    """Os dois nomes possíveis: o original e o desambiguado por hash."""
    slug = _slug(filename)
    return f"{slug}.pdf", f"{slug}__{digest[:12]}.pdf"


def _matches_digest(client, key: str, digest: str) -> bool:
    try:
        head = client.head_object(Bucket=S3_BUCKET, Key=key)
    except ClientError:
        return False
    return head.get("Metadata", {}).get(_HASH_META_KEY) == digest


def stored_path(filename: str, digest: str) -> str | None:
    """Chave do objeto onde este conteúdo está guardado, se estiver."""
    client = _get_client()
    plain, suffixed = _candidate_keys(filename, digest)
    for key in (plain, suffixed):
        if _matches_digest(client, key, digest):
            return key
    return None


def store(filename: str, digest: str, content: bytes) -> None:
    """Guarda o PDF no bucket. Já ter o mesmo conteúdo lá é no-op."""
    try:
        client = _get_client()
        if stored_path(filename, digest) is not None:
            return
        plain, suffixed = _candidate_keys(filename, digest)
        try:
            client.head_object(Bucket=S3_BUCKET, Key=plain)
            target = suffixed
        except ClientError:
            target = plain
        client.put_object(
            Bucket=S3_BUCKET,
            Key=target,
            Body=content,
            ContentType="application/pdf",
            Metadata={_HASH_META_KEY: digest},
        )
    except (ClientError, BotoCoreError):
        logger.warning("Não foi possível guardar '%s' no bucket %s", filename, S3_BUCKET)


def fetch(filename: str, digest: str) -> bytes | None:
    """Baixa o conteúdo do PDF do bucket. `None` se não estiver guardado."""
    try:
        client = _get_client()
        key = stored_path(filename, digest)
        if key is None:
            return None
        return client.get_object(Bucket=S3_BUCKET, Key=key)["Body"].read()
    except (ClientError, BotoCoreError):
        logger.warning("Não foi possível baixar o arquivo de '%s'", filename)
        return None


def discard(filename: str, digest: str) -> None:
    """Remove o objeto deste conteúdo, se ele estiver guardado."""
    try:
        client = _get_client()
        key = stored_path(filename, digest)
        if key is not None:
            client.delete_object(Bucket=S3_BUCKET, Key=key)
    except (ClientError, BotoCoreError):
        logger.warning("Não foi possível remover o arquivo de '%s'", filename)

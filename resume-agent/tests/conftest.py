"""Isolamento dos testes: banco descartável e bucket mockado.

Nenhum teste toca o banco da aplicação. A suíte cria um banco próprio
(`<POSTGRES_DB>_test` por padrão), roda as migrações nele, usa, e derruba no
fim. O mesmo vale para os PDFs: `storage.store`/`storage.discard` são
mockados por um fixture autouse — os testes não precisam de um MinIO de
verdade nem de bucket próprio, e um PDF gravado em um teste nunca vaza pro
próximo.

A URL do banco pode ser trocada mais tarde porque a engine é preguiçosa e
`dispose_engine()` a recria.

Sobrescrever o alvo:
    TEST_POSTGRES_DB=outro_nome pytest
"""

import os
from io import BytesIO

import pytest
from dotenv import load_dotenv
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url

load_dotenv()

from resume_agent.db.engine import database_url, dispose_engine  # noqa: E402
from resume_agent.paths import PROJECT_ROOT  # noqa: E402

SAMPLES_DIR = PROJECT_ROOT / "resumes_samples"

_APP_URL = make_url(database_url())
_APP_DB = _APP_URL.database
_TEST_DB = os.getenv("TEST_POSTGRES_DB") or f"{_APP_DB}_test"

if _TEST_DB == _APP_DB:
    raise RuntimeError(
        f"TEST_POSTGRES_DB não pode ser o banco da aplicação ({_APP_DB!r}). "
        "A suíte apaga e recria o banco que receber."
    )

# `postgres` é o banco de manutenção: CREATE/DROP DATABASE exige estar conectado a outro.
_MAINTENANCE_URL = _APP_URL.set(database="postgres", drivername="postgresql+psycopg")
_TEST_URL = _APP_URL.set(database=_TEST_DB, drivername="postgresql+psycopg")


def _autocommit_connection():
    """Conexão em autocommit: CREATE/DROP DATABASE não roda em transação."""
    return create_engine(_MAINTENANCE_URL, isolation_level="AUTOCOMMIT").connect()


def _drop_test_database() -> None:
    # Sem FORCE o DROP falha se o pool da aplicação ainda estiver conectado.
    with _autocommit_connection() as conn:
        conn.execute(text(f'DROP DATABASE IF EXISTS "{_TEST_DB}" WITH (FORCE)'))


def build_pdf(text: str) -> bytes:
    """PDF mínimo, montado à mão, com `text` como único conteúdo da página.

    Não há gerador de PDF nas dependências do projeto; construir o arquivo
    byte a byte (com tabela xref válida) é o jeito de ter um PDF que o
    `pypdf` (usado por `read_pages`) extrai texto de verdade, sem subir
    nenhuma lib nova só para teste.
    """
    content = f"BT /F1 12 Tf 72 712 Td ({text}) Tj ET"
    objects = [
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 4 0 R >> >> "
        "/MediaBox [0 0 612 792] /Contents 5 0 R >>",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        f"<< /Length {len(content)} >>\nstream\n{content}\nendstream",
    ]

    buf = BytesIO()
    buf.write(b"%PDF-1.4\n")
    offsets = []
    for i, obj in enumerate(objects, start=1):
        offsets.append(buf.tell())
        buf.write(f"{i} 0 obj\n".encode())
        buf.write(obj.encode("latin-1"))
        buf.write(b"\nendobj\n")
    xref_offset = buf.tell()
    n = len(objects) + 1
    buf.write(f"xref\n0 {n}\n".encode())
    buf.write(b"0000000000 65535 f \n")
    for off in offsets:
        buf.write(f"{off:010d} 00000 n \n".encode())
    buf.write(
        f"trailer\n<< /Size {n} /Root 1 0 R >>\nstartxref\n{xref_offset}\n%%EOF".encode()
    )
    return buf.getvalue()


def build_pdf_without_text() -> bytes:
    """PDF válido, página em branco: `extract_text()` devolve string vazia."""
    from pypdf import PdfWriter

    writer = PdfWriter()
    writer.add_blank_page(width=612, height=792)
    buf = BytesIO()
    writer.write(buf)
    return buf.getvalue()


def build_pdf_multipage(*texts: str) -> bytes:
    """PDF com uma página por texto recebido, na ordem."""
    from pypdf import PdfReader, PdfWriter

    writer = PdfWriter()
    for page_text in texts:
        writer.add_page(PdfReader(BytesIO(build_pdf(page_text))).pages[0])
    buf = BytesIO()
    writer.write(buf)
    return buf.getvalue()


@pytest.fixture(scope="session", autouse=True)
def test_database():
    """Cria o banco de teste, migra, e derruba no fim da sessão."""
    _drop_test_database()
    with _autocommit_connection() as conn:
        conn.execute(text(f'CREATE DATABASE "{_TEST_DB}"'))

    # DATABASE_URL tem precedência sobre POSTGRES_*, então isto redireciona tudo.
    os.environ["DATABASE_URL"] = _TEST_URL.render_as_string(hide_password=False)
    dispose_engine()

    from alembic import command
    from alembic.config import Config

    config = Config(str(PROJECT_ROOT / "alembic.ini"))
    command.upgrade(config, "head")

    try:
        yield _TEST_URL
    finally:
        dispose_engine()
        _drop_test_database()


@pytest.fixture(scope="session", autouse=True)
def mocked_storage():
    """Nenhum teste toca o bucket de verdade: `store`/`discard` viram no-op.

    Sessão inteira, não por teste: `populated_database` também é de sessão e
    ingere antes de qualquer teste individual rodar — o mock precisa estar de pé
    antes disso.
    """
    from resume_agent import storage

    original_store, original_discard = storage.store, storage.discard
    storage.store = lambda *a, **k: None
    storage.discard = lambda *a, **k: None
    try:
        yield
    finally:
        storage.store = original_store
        storage.discard = original_discard


@pytest.fixture(scope="session")
def populated_database(test_database):
    """Ingere os currículos de exemplo no banco de teste.

    Separada da criação do banco porque ingerir gera embedding de todos os
    currículos — custa chamada de API. Só quem precisa de base cheia pede.
    """
    from resume_agent.services import document_service, ingestion_service

    pdfs = sorted(SAMPLES_DIR.glob("curriculo_*.pdf"))
    if not pdfs:
        pytest.skip(f"nenhum currículo de exemplo em {SAMPLES_DIR}")

    for pdf in pdfs:
        ingestion_service.ingest_resume(pdf.name, pdf.read_bytes())

    records = document_service.list_inventory()
    assert len(records) == len(pdfs), (
        f"esperava {len(pdfs)} currículos ingeridos, vieram {len(records)}"
    )
    return records

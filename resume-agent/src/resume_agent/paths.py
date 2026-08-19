"""Caminhos de dados do projeto.

Com src-layout o código vive em `src/resume_agent/`, mas os arquivos ficam na
raiz do projeto. `RESUME_AGENT_ROOT` sobrescreve a raiz inferida (útil quando o
pacote é instalado fora da árvore do repositório).

Os PDFs enviados por upload vivem no bucket MinIO, não no disco do projeto
(ver `resume_agent.storage`). A base vetorial também não: vive no
Postgres/pgvector (ver `resume_agent.db.engine`).
"""

import os
from pathlib import Path

PROJECT_ROOT = Path(
    os.getenv("RESUME_AGENT_ROOT", Path(__file__).resolve().parents[2])
)

MIGRATIONS_DIR = PROJECT_ROOT / "migrations"

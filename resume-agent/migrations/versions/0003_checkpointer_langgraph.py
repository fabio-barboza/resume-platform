"""Tabelas do checkpointer do LangGraph: histórico de conversa no banco.

O histórico vivia num dict em memória do processo, o que quebra com mais de
uma réplica: o turno seguinte cai em outro pod, que não conhece o
`session_id`, e o agente responde sem contexto. Persistir o estado no Postgres
é o que permite escalar horizontalmente.

O DDL não está escrito aqui de propósito. O `langgraph-checkpoint-postgres`
mantém a própria tabela de controle (`checkpoint_migrations`) e o `setup()`
dele é idempotente: aplica só o delta que faltar. Copiar o SQL à mão criaria
uma segunda fonte da verdade que silenciosamente diverge no primeiro upgrade
da biblioteca.

O preço: o que esta revisão executa depende da versão instalada. Por isso a
dependência está presa por `==` no `pyproject.toml`. Ao subir a versão,
verifique o `MIGRATIONS` da lib e, se houver passo novo, adicione uma revisão
nova chamando `setup()` de novo — não edite esta.

Revision ID: 0003
Revises: 0002
"""

from collections.abc import Sequence

from alembic import op

revision: str = "0003"
down_revision: str | None = "0002"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# Ordem de drop: nenhuma tem FK entre si, mas `checkpoint_migrations` é o
# controle de versão da lib e sai por último para o estado nunca ficar "migrado
# segundo o controle, sem tabela nenhuma".
_TABLES = (
    "checkpoint_writes",
    "checkpoint_blobs",
    "checkpoints",
    "checkpoint_migrations",
)


def upgrade() -> None:
    from langgraph.checkpoint.postgres import PostgresSaver

    from resume_agent.db.engine import psycopg_url

    # Conexão própria em vez da do Alembic: `setup()` exige autocommit e
    # `row_factory=dict_row`, e a conexão do Alembic está dentro de uma
    # transação com row factory de tupla. `from_conn_string` já abre do jeito
    # que a lib espera.
    with PostgresSaver.from_conn_string(psycopg_url()) as saver:
        saver.setup()


def downgrade() -> None:
    for table in _TABLES:
        op.execute(f"DROP TABLE IF EXISTS {table}")

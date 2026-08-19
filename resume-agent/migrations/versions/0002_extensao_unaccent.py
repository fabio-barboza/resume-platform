"""Extensão unaccent: busca de candidato por nome sem depender de acento.

Nome de pessoa não é recuperável por embedding — o vetor de "Bruno Carvalho"
fica tão perto de qualquer outro currículo quanto do dele. A busca por nome
passou a ser lexical, e lexical em português precisa casar "Marcia" com
"Márcia".

Revision ID: 0002
Revises: 0001
"""

from collections.abc import Sequence

from alembic import op

revision: str = "0002"
down_revision: str | None = "0001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute("CREATE EXTENSION IF NOT EXISTS unaccent")


def downgrade() -> None:
    op.execute("DROP EXTENSION IF EXISTS unaccent")

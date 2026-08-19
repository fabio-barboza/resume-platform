"""Ambiente do Alembic.

`target_metadata` aponta para os modelos declarativos: é o que o
`alembic revision --autogenerate` compara com o banco. A URL de conexão sai do
.env (DATABASE_URL ou POSTGRES_*), com o driver já resolvido.
"""

from alembic import context
from sqlalchemy import engine_from_config, pool

from resume_agent.db.engine import sqlalchemy_url
from resume_agent.db.models import Base

config = context.config
config.set_main_option("sqlalchemy.url", sqlalchemy_url())

target_metadata = Base.metadata


def run_migrations_offline() -> None:
    context.configure(
        url=config.get_main_option("sqlalchemy.url"),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    connectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )
    with connectable.connect() as connection:
        context.configure(connection=connection, target_metadata=target_metadata)
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
